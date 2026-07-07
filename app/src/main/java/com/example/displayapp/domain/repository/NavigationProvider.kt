package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.NavProgress
import kotlinx.coroutines.flow.Flow

/**
 * The **provider-neutral seam** for turn-by-turn navigation. A concrete routing SDK
 * (Mapbox Nav, HERE, TomTom, Valhalla/GraphHopper + a position tracker, …) is
 * integrated by implementing this one interface: translate the SDK's route-progress /
 * banner-instruction objects into [NavProgress] + the domain [com.example.displayapp.domain.model.Maneuver]
 * enum, and surface its off-route / reroute / arrival callbacks as
 * [com.example.displayapp.domain.model.NavState] transitions.
 *
 * Nothing below this interface (RouteNavigator, the encoder, the BLE transport, the
 * firmware contract) depends on the chosen SDK, so swapping providers is a one-class
 * change.
 */
interface NavigationProvider {

    /**
     * Emissions of the current [NavProgress], one per GPS fix / SDK tick. The first
     * emission after [start] should carry `state = Navigating` with the route totals
     * populated (so RouteNavigator can emit the initial `RouteSummary`); a new
     * `routeId` signals a reroute/new route; a terminal state ends the session.
     */
    val progress: Flow<NavProgress>

    /** Begin routing to [destination] and start emitting [progress]. */
    suspend fun start(destination: GeoLocation)

    /** Stop navigation and release SDK resources. Emits a terminal [NavProgress]. */
    fun stop()
}
