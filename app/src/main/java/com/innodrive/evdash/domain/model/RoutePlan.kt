package com.innodrive.evdash.domain.model

/**
 * A planned route, provider-neutral. A [com.innodrive.evdash.domain.repository.RoutePlanner]
 * (GraphHopper today, ORS/HERE later) produces this; the provider-independent
 * `RouteProgressTracker` consumes it together with live GPS to emit [NavProgress].
 *
 * Nothing here references a routing SDK — geometry is domain [GeoLocation] points and
 * maneuvers use the domain [Maneuver] enum, so the routing provider is swappable and
 * `NavProgress` remains the single source of truth for both the map overlay and the
 * BLE `NavInstruction` stream.
 */
data class RoutePlan(
    val origin: GeoLocation,
    val destination: GeoLocation,
    /** Full route geometry (dense), for drawing and map-matching. */
    val polyline: List<GeoLocation>,
    /** Turn-by-turn maneuvers in order. */
    val maneuvers: List<RouteManeuver>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val destinationName: String = "",
)

/**
 * One turn instruction along a [RoutePlan].
 *
 * @param maneuver icon/type (provider-neutral)
 * @param location where the maneuver occurs (the turn point)
 * @param streetName the road this instruction leads onto
 * @param distanceMeters length travelled while following this instruction (to the next)
 * @param roundaboutExit exit number for roundabout maneuvers, else 0
 * @param polylineIndex index into [RoutePlan.polyline] where this maneuver begins —
 *        lets the tracker map a maneuver to a distance-along-route without any SDK types
 */
data class RouteManeuver(
    val maneuver: Maneuver,
    val location: GeoLocation,
    val streetName: String,
    val distanceMeters: Int,
    val roundaboutExit: Int = 0,
    val polylineIndex: Int = 0,
)
