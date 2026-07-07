package com.example.displayapp.data.navigation

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.NavigationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The one live navigation session, shared app-wide. Picking a destination starts a
 * single session whose **`NavProgress` is the single source of truth**, consumed by:
 *  - the **BLE `NavInstruction` stream** — [routeNavigator] turns [NavigationProvider]
 *    progress into frames to the controller, and
 *  - the **phone map overlay** — screens read [activeRoute] (route geometry) + [progress]
 *    (live state) from here.
 *
 * Everything stays provider-independent: the coordinator only knows the
 * [NavigationProvider] port (GraphHopper today) and the renderer-neutral [RoutePlan].
 * Held as an app-scoped singleton so the Drive mini-map and the fullscreen Navigation
 * screen observe the same session.
 */
class NavigationCoordinator(
    private val provider: NavigationProvider,
    private val routeNavigator: RouteNavigator,
    scope: CoroutineScope,
) {
    private val progressFlow: Flow<NavProgress?> = provider.progress // widen to nullable

    /** Live navigation state (map banner / gating); null when idle. */
    val progress: StateFlow<NavProgress?> =
        progressFlow.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Active route geometry for the map overlay; null when idle. */
    val activeRoute: StateFlow<RoutePlan?> =
        provider.activeRoute.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Start (or replace) the session — streams to BLE and lights up the map overlay. */
    fun navigateTo(destination: GeoLocation) = routeNavigator.startNavigation(destination)

    /** End the session — sends a cancel to the controller and clears the overlay. */
    fun cancel() = routeNavigator.cancelNavigation()
}
