package com.innodrive.evdash.data.navigation

import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.NavProgress
import com.innodrive.evdash.domain.model.NavState
import com.innodrive.evdash.domain.model.RouteManeuver
import com.innodrive.evdash.domain.model.RoutePlan
import com.innodrive.evdash.domain.repository.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two session-start bugs behind "Start Navigation does
 * nothing":
 *
 * 1. start() used to wait for a GPS fix and RE-PLAN the just-confirmed route — a
 *    second network request that could fail and silently cancel the session. With
 *    an [RoutePlan] passed in, progress + activeRoute must appear immediately,
 *    with no planner call.
 * 2. stop() left a terminal Cancelled in the SharedFlow's replay cache; the next
 *    session's collector received it instantly and RouteNavigator killed the new
 *    session at birth. After the resetReplayCache fix, a new session must never
 *    see the previous session's terminal state.
 */
class GraphHopperNavigationProviderTest {

    private class CountingPlanner : RoutePlanner {
        var calls = 0
        override val isConfigured = true
        override suspend fun plan(origin: GeoLocation, destination: GeoLocation): RoutePlan? {
            calls++
            return null // network down — must not matter when an initial plan is given
        }
    }

    private fun plan(dest: GeoLocation) = RoutePlan(
        origin = GeoLocation(0.0, 0.0),
        destination = dest,
        polyline = (0..10).map { GeoLocation(0.0, it * 0.001) },
        maneuvers = listOf(
            RouteManeuver(Maneuver.Depart, GeoLocation(0.0, 0.0), "A", 0, polylineIndex = 0),
            RouteManeuver(Maneuver.Arrive, GeoLocation(0.0, 0.01), "B", 0, polylineIndex = 10),
        ),
        distanceMeters = 1100,
        durationSeconds = 120,
    )

    @Test
    fun `adopts the confirmed preview plan - immediate progress, no replan`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val planner = CountingPlanner()
        val provider = GraphHopperNavigationProvider(planner, MutableSharedFlow(), scope)

        val received = mutableListOf<NavProgress>()
        val collector = scope.launch { provider.progress.collect { received.add(it) } }

        val dest = GeoLocation(0.0, 0.01)
        provider.start(dest, plan(dest))

        assertTrue("progress emitted without any GPS fix", received.isNotEmpty())
        assertEquals(NavState.Navigating, received.first().state)
        assertNotNull("route geometry exposed immediately", provider.activeRoute.value)
        assertEquals("no replanning of the confirmed route", 0, planner.calls)

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `previous session's terminal state cannot kill the next session`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val provider = GraphHopperNavigationProvider(CountingPlanner(), MutableSharedFlow(), scope)
        val dest = GeoLocation(0.0, 0.01)

        // Session 1: start + user cancel → Cancelled lands in the replay cache.
        provider.start(dest, plan(dest))
        provider.stop()

        // Session 2: a fresh collector (as RouteNavigator subscribes per session)
        // must NOT be handed the stale Cancelled — first thing it sees is the new
        // session's own Navigating emission.
        val received = mutableListOf<NavProgress>()
        val collector = scope.launch { provider.progress.collect { received.add(it) } }
        provider.start(dest, plan(dest))

        assertTrue("new session emits progress", received.isNotEmpty())
        assertTrue(
            "no stale terminal state delivered (got ${received.map { it.state }})",
            received.none { it.state.isTerminal },
        )
        assertEquals(NavState.Navigating, received.first().state)

        collector.cancel()
        scope.cancel()
    }
}
