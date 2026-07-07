package com.example.displayapp.data.navigation

import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.NavigationSchema
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.Maneuver
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.NavState
import com.example.displayapp.domain.model.RouteManeuver
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.NavigationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral test for [RouteNavigator] using a fake provider + fake transport (no BLE,
 * no routing SDK). Runs on an unconfined scope so emissions drive the orchestrator
 * synchronously, and injects a fixed clock. Confirms the demo/production path produces
 * the right frames in the right order with the right write reliability.
 */
class RouteNavigatorTest {

    private class FakeTransport : BluetoothDataSource {
        val navFrames = mutableListOf<Pair<ByteArray, Boolean>>()
        override val incomingData: SharedFlow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.CONNECTED).asStateFlow()
        override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> =
            MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList()).asStateFlow()
        override val rssi: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
        override fun startDiscovery() {}
        override fun stopDiscovery() {}
        override suspend fun connect(address: String) {}
        override fun disconnect() {}
        override fun close() {}
        override suspend fun writeNav(frame: ByteArray, reliable: Boolean): Boolean {
            navFrames += frame to reliable
            return true
        }
    }

    private class FakeProvider : NavigationProvider {
        private val flow = MutableSharedFlow<NavProgress>(extraBufferCapacity = 16)
        val plan = MutableStateFlow<RoutePlan?>(null)
        override val progress: Flow<NavProgress> = flow
        override val activeRoute: Flow<RoutePlan?> = plan
        override suspend fun start(destination: GeoLocation) {}
        override fun stop() {}
        suspend fun emit(p: NavProgress) = flow.emit(p)
    }

    /** navType is the third frame byte: [0xAA][LEN][navType]…. */
    private fun navType(frame: ByteArray) = frame[2].toInt() and 0xFF

    @Test
    fun streams_summary_then_instruction_then_reliable_terminal() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val transport = FakeTransport()
        val provider = FakeProvider()
        val nav = RouteNavigator(provider, transport, scope, now = { 0L })

        nav.startNavigation(GeoLocation(0.0, 0.0))

        runBlocking {
            provider.emit(
                NavProgress(
                    routeId = 1L, state = NavState.Navigating, maneuver = Maneuver.TurnLeft,
                    distanceToTurnM = 200, streetName = "Jl. Sudirman",
                    distanceRemainingM = 5300, etaSeconds = 840, speedKmh = 20,
                    totalDistanceM = 5300, totalDurationSec = 840, destinationName = "Kantor",
                ),
            )
        }

        // First progress on a new route → RouteSummary (reliable) then NavInstruction.
        assertEquals(2, transport.navFrames.size)
        assertEquals(NavigationSchema.NAV_TYPE_ROUTE_SUMMARY, navType(transport.navFrames[0].first))
        assertTrue("RouteSummary is a reliable write", transport.navFrames[0].second)
        assertEquals(NavigationSchema.NAV_TYPE_NAV_INSTRUCTION, navType(transport.navFrames[1].first))
        assertTrue("mid-route instruction is a stream (WWR) write", !transport.navFrames[1].second)

        // Terminal state → a reliable NavInstruction, session ends.
        runBlocking {
            provider.emit(
                NavProgress(routeId = 1L, state = NavState.Arrived, maneuver = Maneuver.Arrive),
            )
        }
        assertEquals(3, transport.navFrames.size)
        val terminal = transport.navFrames[2]
        assertEquals(NavigationSchema.NAV_TYPE_NAV_INSTRUCTION, navType(terminal.first))
        assertTrue("terminal (arrived) is a reliable write", terminal.second)

        scope.cancel()
    }

    @Test
    fun sends_route_geometry_to_the_controller_on_a_new_route() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val transport = FakeTransport()
        val provider = FakeProvider()
        val nav = RouteNavigator(provider, transport, scope, now = { 0L })

        // A route with a handful of points → geometry to chunk + send.
        provider.plan.value = RoutePlan(
            origin = GeoLocation(0.0, 0.0),
            destination = GeoLocation(0.0, 0.01),
            polyline = (0..10).map { GeoLocation(0.0, it * 0.001) },
            maneuvers = listOf(
                RouteManeuver(Maneuver.Depart, GeoLocation(0.0, 0.0), "A", 0, polylineIndex = 0),
            ),
            distanceMeters = 1100, durationSeconds = 120, destinationName = "Dest",
        )

        nav.startNavigation(GeoLocation(0.0, 0.0)) // collector picks up plan (Unconfined)
        runBlocking {
            provider.emit(
                NavProgress(
                    routeId = 1L, state = NavState.Navigating, maneuver = Maneuver.TurnLeft,
                    totalDistanceM = 1100, totalDurationSec = 120, destinationName = "Dest",
                    maneuverCount = 1,
                ),
            )
        }

        val types = transport.navFrames.map { navType(it.first) }
        // Route summary first, then the route geometry, then the live instruction.
        assertEquals(NavigationSchema.NAV_TYPE_ROUTE_SUMMARY, types.first())
        assertTrue("route geometry (RouteChunk) is sent",
            types.contains(NavigationSchema.NAV_TYPE_ROUTE_CHUNK))
        assertEquals(NavigationSchema.NAV_TYPE_NAV_INSTRUCTION, types.last())
        // Summary + all chunks are reliable writes.
        transport.navFrames.dropLast(1).forEach {
            assertTrue("route frames are reliable", it.second)
        }
        scope.cancel()
    }

    @Test
    fun same_maneuver_without_time_or_state_change_is_not_resent() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val transport = FakeTransport()
        val provider = FakeProvider()
        // Frozen clock → heartbeat never elapses; only maneuver/state changes send.
        val nav = RouteNavigator(provider, transport, scope, now = { 1_000L })

        nav.startNavigation(GeoLocation(0.0, 0.0))
        val p = NavProgress(
            routeId = 1L, state = NavState.Navigating, maneuver = Maneuver.TurnLeft,
            distanceToTurnM = 200, streetName = "A", distanceRemainingM = 100, etaSeconds = 30,
            speedKmh = 20,
        )
        runBlocking { provider.emit(p) }                 // summary + instruction
        runBlocking { provider.emit(p.copy(distanceToTurnM = 140)) } // same maneuver, no heartbeat

        // Only the initial summary + one instruction; the redundant update is suppressed.
        assertEquals(2, transport.navFrames.size)
        scope.cancel()
    }
}
