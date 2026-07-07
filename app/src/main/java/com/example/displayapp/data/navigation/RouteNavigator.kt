package com.example.displayapp.data.navigation

import android.os.SystemClock
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.NavigationSchema
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.NavState
import com.example.displayapp.domain.repository.NavigationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Orchestrates turn-by-turn navigation → BLE. Subscribes to a provider-neutral
 * [NavigationProvider] and streams compact `NavInstruction` frames (plus a one-shot
 * `RouteSummary` per route) to the controller over the nav characteristic (0xAF06)
 * via [BluetoothDataSource.writeNav].
 *
 * Send policy (docs/NAVIGATION-INTEGRATION.md §5–6):
 *  - **RouteSummary** once per `routeId` (start / reroute) — reliable write.
 *  - **NavInstruction** on maneuver change or state change (immediate), else a
 *    heartbeat at ≤1 Hz (0.2 Hz while stationary). Frames are full state, so a
 *    dropped stream frame self-heals on the next.
 *  - **Terminal** (arrived/cancelled) — reliable write, then the session ends.
 *  - **Reconnect** while a session is active — re-send the cached RouteSummary
 *    (the board lost its state), then resume the stream.
 *
 * All timing uses monotonic [SystemClock.elapsedRealtime]. This class holds no
 * routing-SDK types — those live behind [NavigationProvider].
 */
class RouteNavigator(
    private val provider: NavigationProvider,
    private val transport: BluetoothDataSource,
    private val scope: CoroutineScope,
    /** Monotonic clock for send throttling; injectable for deterministic tests. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var sessionJob: Job? = null
    private var seq = 0

    // Per-session send-state (guarded by single-collector confinement).
    private var active = false
    private var lastSentRouteId = -1L
    private var lastSummary: NavProgress? = null
    private var lastManeuverKey: Int? = null
    private var lastState: NavState? = null
    private var lastSendMs = 0L

    init {
        // Re-seed the board's route context after a reconnect mid-session.
        transport.connectionState
            .onEach { if (it == ConnectionState.CONNECTED && active) resendSummary() }
            .launchIn(scope)
    }

    /** Start navigating to [destination]; begins the frame stream. */
    fun startNavigation(destination: GeoLocation) {
        stopInternal(sendCancel = false)
        active = true
        resetSessionState()
        sessionJob = scope.launch {
            // Subscribe concurrently with start(): some providers (the simulator, and
            // any SDK that drives progress from within start()) emit before start()
            // returns, so collecting *after* it would miss everything but the last
            // replayed value. The provider's SharedFlow replay covers the subscribe race.
            launch { provider.progress.collect { onProgress(it) } }
            provider.start(destination)
        }
    }

    /** Stop navigating; tells the board to clear the nav UI. */
    fun cancelNavigation() {
        if (!active) return
        val last = lastSummary
        scope.launch {
            transport.writeNav(
                NavigationSchema.frameNavInstruction(
                    instruction(
                        NavProgress(
                            routeId = last?.routeId ?: 0L,
                            state = NavState.Cancelled,
                            maneuver = com.example.displayapp.domain.model.Maneuver.None,
                        ),
                    ),
                ),
                reliable = true,
            )
        }
        stopInternal(sendCancel = false)
    }

    private suspend fun onProgress(p: NavProgress) {
        // New route (initial or reroute) → announce it first, reliably.
        if (p.routeId != lastSentRouteId && p.state == NavState.Navigating) {
            lastSummary = p
            transport.writeNav(NavigationSchema.frameRouteSummary(summary(p)), reliable = true)
            lastSentRouteId = p.routeId
        }

        val maneuverKey = maneuverKey(p)
        val maneuverChanged = maneuverKey != lastManeuverKey
        val stateChanged = p.state != lastState
        val heartbeatDue = now() - lastSendMs >= sendIntervalMs(p)

        if (maneuverChanged || stateChanged || heartbeatDue) {
            transport.writeNav(
                NavigationSchema.frameNavInstruction(instruction(p, seq++)),
                reliable = p.state.isTerminal,
            )
            lastManeuverKey = maneuverKey
            lastState = p.state
            lastSendMs = now()
        }

        if (p.state.isTerminal) stopInternal(sendCancel = false)
    }

    private suspend fun resendSummary() {
        val s = lastSummary ?: return
        Timber.d("Nav: reconnect — re-seeding RouteSummary for route ${s.routeId}")
        transport.writeNav(NavigationSchema.frameRouteSummary(summary(s)), reliable = true)
        lastManeuverKey = null // force the next instruction to send immediately
    }

    private fun stopInternal(sendCancel: Boolean) {
        active = false
        sessionJob?.cancel()
        sessionJob = null
        provider.stop()
        if (sendCancel) cancelNavigation()
    }

    private fun resetSessionState() {
        seq = 0
        lastSentRouteId = -1L
        lastSummary = null
        lastManeuverKey = null
        lastState = null
        lastSendMs = 0L
    }

    /** Send cadence: immediate on non-navigating states, 0.2 Hz stationary, else 1 Hz. */
    private fun sendIntervalMs(p: NavProgress): Long = when {
        p.state != NavState.Navigating -> 0L
        p.speedKmh < 2 -> 5_000L
        else -> 1_000L
    }

    private fun maneuverKey(p: NavProgress): Int =
        (p.routeId.toInt() * 31) xor (p.maneuver.wire shl 8) xor p.roundaboutExit

    private fun summary(p: NavProgress) = NavigationSchema.RouteSummary(
        routeId = p.routeId,
        totalDistanceM = u32(p.totalDistanceM),
        totalDurationSec = u32(p.totalDurationSec),
        maneuverCount = u16(p.maneuverCount),
        polylineChunks = 0, // phase 2
        schemaVersion = SCHEMA_VERSION,
        destinationName = p.destinationName,
    )

    private fun instruction(p: NavProgress, seqValue: Int = seq) = NavigationSchema.NavInstruction(
        routeId = p.routeId,
        distanceRemainingM = u32(p.distanceRemainingM),
        etaSeconds = u32(p.etaSeconds),
        seq = u16(seqValue),
        state = p.state.wire,
        maneuver = p.maneuver.wire,
        distanceToTurnM = u16(p.distanceToTurnM),
        nextManeuver = p.nextManeuver.wire,
        nextDistanceM = u16(p.nextDistanceM),
        roundaboutExit = u8(p.roundaboutExit),
        speedLimitKmh = u8(p.speedLimitKmh),
        streetName = p.streetName,
    )

    private fun u8(v: Int) = v.coerceIn(0, 0xFF)
    private fun u16(v: Int) = v.coerceIn(0, 0xFFFF)
    private fun u32(v: Int) = v.coerceAtLeast(0).toLong()

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
