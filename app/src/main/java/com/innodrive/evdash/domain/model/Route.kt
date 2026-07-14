package com.innodrive.evdash.domain.model

/**
 * Computed route between two points.
 *
 * Currently a domain shape only — populated by a stub when the user picks a
 * destination. The data layer can swap in a real Directions API call later by
 * implementing a `RouteRepository` that returns this same model.
 *
 * @param origin start point (usually the vehicle's current GPS fix)
 * @param destination end point selected via search
 * @param polyline the path as a list of waypoints for drawing on the map
 * @param distanceMeters total route distance
 * @param durationSeconds estimated travel time at current traffic
 */
data class Route(
    val origin: GeoLocation,
    val destination: GeoLocation,
    val polyline: List<GeoLocation> = emptyList(),
    val distanceMeters: Int = 0,
    val durationSeconds: Int = 0
)
