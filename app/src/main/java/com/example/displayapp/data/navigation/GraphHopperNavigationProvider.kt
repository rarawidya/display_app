package com.example.displayapp.data.navigation

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.Maneuver
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.NavState
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.NavigationProvider
import com.example.displayapp.domain.repository.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Production [NavigationProvider] = a [RoutePlanner] (GraphHopper) + the
 * provider-independent [RouteProgressTracker]. It plans a route from the first fix,
 * then feeds each subsequent [GeoLocation] through the tracker and emits the resulting
 * [NavProgress] — the single source of truth consumed by both the Drive map overlay
 * and, via `RouteNavigator`, the BLE `NavInstruction` stream.
 *
 * Off-route handling lives here (a policy decision, not the tracker's): after a few
 * consecutive off-route fixes it asks the [RoutePlanner] to replan from the current
 * position and bumps `routeId` (the board's/overlay's "discard and re-learn" signal).
 *
 * Swapping the routing provider is a different [RoutePlanner] passed in — this class,
 * the tracker, the map overlay, and the BLE protocol are unchanged.
 */
class GraphHopperNavigationProvider(
    private val planner: RoutePlanner,
    private val locations: Flow<GeoLocation>,
    private val scope: CoroutineScope,
) : NavigationProvider {

    private val _progress = MutableSharedFlow<NavProgress>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val progress: Flow<NavProgress> = _progress.asSharedFlow()

    // The current route geometry, for the map overlay (same session as the BLE stream).
    private val _activeRoute = MutableStateFlow<RoutePlan?>(null)
    override val activeRoute: StateFlow<RoutePlan?> = _activeRoute.asStateFlow()

    private var job: Job? = null
    private var routeId = 0L
    @Volatile private var destination: GeoLocation? = null
    @Volatile private var tracker: RouteProgressTracker? = null
    @Volatile private var offRouteStreak = 0

    override suspend fun start(destination: GeoLocation, initialPlan: RoutePlan?) {
        teardown(emitCancel = false)
        // A terminal state from the PREVIOUS session lives in the replay cache —
        // without this, the new session's collector receives that stale
        // Cancelled/Arrived instantly and RouteNavigator ends the session before
        // it starts ("Start Navigation does nothing" after any prior cancel).
        _progress.resetReplayCache()
        this.destination = destination
        if (initialPlan != null) {
            // Adopt the confirmed preview route: no second routing request (which
            // could fail and silently cancel), and progress starts NOW instead of
            // after the next GPS fix — the map flips to navigating and the board
            // receives RouteSummary/RouteChunks immediately.
            routeId += 1
            val t = RouteProgressTracker(initialPlan, routeId)
            tracker = t
            _activeRoute.value = initialPlan
            offRouteStreak = 0
            _progress.tryEmit(t.onLocation(initialPlan.origin))
        }
        job = scope.launch {
            locations.collect { onFix(it) }
        }
    }

    private suspend fun onFix(fix: GeoLocation) {
        val dest = destination ?: return

        // First fix (or after a reroute cleared the tracker) → plan a route.
        var t = tracker
        if (t == null) {
            val plan = planner.plan(fix, dest)
            if (plan == null) {
                Timber.w("GraphHopper: no route to $dest")
                emitTerminal(NavState.Cancelled)
                teardown(emitCancel = false)
                return
            }
            routeId += 1
            t = RouteProgressTracker(plan, routeId)
            tracker = t
            _activeRoute.value = plan
            offRouteStreak = 0
        }

        val p = t.onLocation(fix)
        when (p.state) {
            NavState.OffRoute -> {
                offRouteStreak++
                _progress.tryEmit(p.copy(state = NavState.Rerouting))
                if (offRouteStreak >= REROUTE_AFTER_FIXES) {
                    val replan = planner.plan(fix, dest)
                    if (replan != null) {
                        routeId += 1
                        tracker = RouteProgressTracker(replan, routeId)
                        _activeRoute.value = replan
                        offRouteStreak = 0
                    }
                }
            }
            NavState.Arrived -> {
                offRouteStreak = 0
                _progress.tryEmit(p)
                teardown(emitCancel = false) // session done; no cancel frame
            }
            else -> {
                offRouteStreak = 0
                _progress.tryEmit(p)
            }
        }
    }

    override fun stop() = teardown(emitCancel = true)

    private fun teardown(emitCancel: Boolean) {
        job?.cancel()
        job = null
        tracker = null
        _activeRoute.value = null
        offRouteStreak = 0
        val hadSession = destination != null
        destination = null
        if (emitCancel && hadSession) emitTerminal(NavState.Cancelled)
        // The terminal state was just delivered live to this session's collectors;
        // wipe it from the replay cache so the NEXT session's collector (which may
        // subscribe before start() runs) can never receive it and kill the new
        // session at birth ("Start Navigation does nothing" after a cancel).
        _progress.resetReplayCache()
    }

    private fun emitTerminal(state: NavState) {
        _progress.tryEmit(NavProgress(routeId = routeId, state = state, maneuver = Maneuver.None))
    }

    private companion object {
        const val REROUTE_AFTER_FIXES = 3
    }
}
