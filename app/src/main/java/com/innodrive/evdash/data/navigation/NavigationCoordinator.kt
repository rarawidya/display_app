package com.innodrive.evdash.data.navigation

import com.innodrive.evdash.data.preferences.LastRouteStore
import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.NavProgress
import com.innodrive.evdash.domain.model.RoutePlan
import com.innodrive.evdash.domain.repository.NavigationProvider
import com.innodrive.evdash.domain.repository.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one live navigation session, shared app-wide, with an explicit **preview →
 * confirm → navigate** lifecycle:
 *
 *  1. [preview] a destination — plans the route (via [RoutePlanner]) and exposes
 *     [previewRoute] (distance/ETA/geometry) for a confirmation sheet + a map preview.
 *     **No session is started.**
 *  2. [startPending] on confirm — creates the navigation session, whose `NavProgress`
 *     then drives both the map overlay ([activeRoute] + [progress]) and the BLE
 *     `NavInstruction` stream (via [RouteNavigator]).
 *  3. [dismissPreview] / [cancel] to back out.
 *
 * Everything stays provider-independent (the [NavigationProvider]/[RoutePlanner] ports
 * and the neutral [RoutePlan]). Held as an app-scoped singleton so the Drive mini-map
 * and the Navigation screen observe the same session and preview.
 */
class NavigationCoordinator(
    private val provider: NavigationProvider,
    private val routeNavigator: RouteNavigator,
    private val routePlanner: RoutePlanner,
    locations: Flow<GeoLocation>,
    private val scope: CoroutineScope,
    /** When set, every activated route (start/reroute) is persisted for the Home
     *  page's Last Ride map thumbnail — the only place route geometry outlives
     *  the session (trips don't record GPS). */
    private val lastRouteStore: LastRouteStore? = null,
) {
    private val progressFlow: Flow<NavProgress?> = provider.progress

    /** Live navigation state (once started); null when idle. */
    val progress: StateFlow<NavProgress?> =
        progressFlow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Active route geometry for the map overlay (once started); null when idle. */
    val activeRoute: StateFlow<RoutePlan?> =
        provider.activeRoute.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val _previewDestination = MutableStateFlow<GeoLocation?>(null)

    /** Destination awaiting confirmation (for the marker + sheet); null when none. */
    val previewDestination: StateFlow<GeoLocation?> = _previewDestination.asStateFlow()

    private val _previewRoute = MutableStateFlow<RoutePlan?>(null)

    /** Planned preview route (distance/ETA/geometry); null while planning or none. */
    val previewRoute: StateFlow<RoutePlan?> = _previewRoute.asStateFlow()

    private val _previewError = MutableStateFlow<String?>(null)

    /**
     * Non-null when preview planning failed — carries the human-readable reason
     * (typed GraphHopper error, or "no GPS fix"). Without this the sheet spins on
     * "Calculating route…" forever with Start disabled; the UI shows the reason +
     * Retry instead. Null means "planning" or "succeeded".
     */
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    @Volatile private var lastLocation: GeoLocation? = null
    @Volatile private var activeDestinationName: String = ""
    private var previewJob: Job? = null

    init {
        locations.onEach { lastLocation = it }.launchIn(scope)
        // Remember the latest activated route (start or reroute) for the Home
        // page's Last Ride thumbnail. Distinct-until-changed via StateFlow.
        if (lastRouteStore != null) {
            scope.launch {
                provider.activeRoute.filterNotNull().collect { plan ->
                    lastRouteStore.save(plan, destinationName = activeDestinationName)
                }
            }
        }
    }

    /** Plan a route to [destination] for confirmation — does NOT start navigation. */
    fun preview(destination: GeoLocation) {
        _previewDestination.value = destination
        _previewRoute.value = null // "planning…"
        _previewError.value = null
        previewJob?.cancel()
        previewJob = scope.launch {
            val origin = lastLocation
            if (origin == null) {
                // No fix to plan from — fail visibly instead of spinning forever.
                _previewError.value = "Waiting for a GPS fix — try again in a moment."
                return@launch
            }
            val plan = routePlanner.plan(origin, destination)
            if (plan == null) {
                // Prefer the planner's specific reason (bad key / quota / offline);
                // a null lastError with a null plan means "no route found".
                _previewError.value = routePlanner.lastError ?: "Couldn't find a route to there."
            } else {
                _previewRoute.value = plan
            }
        }
    }

    /** Re-plan the pending preview after a failure (the Retry action). */
    fun retryPreview() {
        _previewDestination.value?.let { preview(it) }
    }

    /**
     * Confirm the previewed destination → start the session (map overlay + BLE).
     * [destinationName] is the picked place's label, forwarded to the board's
     * RouteSummary (the planner only knows coordinates, not names).
     */
    fun startPending(destinationName: String = "") {
        val dest = _previewDestination.value ?: return
        // Hand the previewed plan to the session — the user confirmed THIS route;
        // re-planning costs a second network round-trip and, when it fails, kills
        // the session before anything is drawn ("Start does nothing").
        val plan = _previewRoute.value
        clearPreview()
        activeDestinationName = destinationName
        routeNavigator.startNavigation(dest, destinationName, plan)
    }

    /** Discard the pending preview without starting navigation. */
    fun dismissPreview() = clearPreview()

    /** End an active session (and any preview). */
    fun cancel() {
        routeNavigator.cancelNavigation()
        clearPreview()
    }

    private fun clearPreview() {
        previewJob?.cancel()
        _previewDestination.value = null
        _previewRoute.value = null
        _previewError.value = null
    }
}
