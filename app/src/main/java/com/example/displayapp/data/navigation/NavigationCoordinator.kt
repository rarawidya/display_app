package com.example.displayapp.data.navigation

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.NavigationProvider
import com.example.displayapp.domain.repository.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Volatile private var lastLocation: GeoLocation? = null
    private var previewJob: Job? = null

    init {
        locations.onEach { lastLocation = it }.launchIn(scope)
    }

    /** Plan a route to [destination] for confirmation — does NOT start navigation. */
    fun preview(destination: GeoLocation) {
        _previewDestination.value = destination
        _previewRoute.value = null // "planning…"
        previewJob?.cancel()
        previewJob = scope.launch {
            val origin = lastLocation ?: return@launch
            _previewRoute.value = routePlanner.plan(origin, destination)
        }
    }

    /** Confirm the previewed destination → start the session (map overlay + BLE). */
    fun startPending() {
        val dest = _previewDestination.value ?: return
        clearPreview()
        routeNavigator.startNavigation(dest)
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
    }
}
