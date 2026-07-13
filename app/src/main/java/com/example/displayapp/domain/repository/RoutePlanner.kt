package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.RoutePlan

/**
 * Provider port for **route planning** (origin → destination → geometry + maneuvers).
 * This is the only seam a routing SDK touches: `GraphHopperRoutePlanner` implements it
 * today; ORS/HERE/self-hosted are drop-in replacements.
 *
 * Deliberately separate from navigation *progress*: planning yields a static
 * [RoutePlan]; the provider-independent `RouteProgressTracker` turns that plan + live
 * GPS into the [com.example.displayapp.domain.model.NavProgress] stream. So neither the
 * tracker, the map overlay, nor the BLE `NavInstruction` path depends on the routing SDK.
 */
interface RoutePlanner {

    /** True when the planner is usable (e.g. an API key/endpoint is configured). */
    val isConfigured: Boolean

    /**
     * Human-readable reason the most recent [plan] returned null due to an error
     * (bad key, quota, offline, …), or null when the last call succeeded or found
     * no route. Provider-neutral so the UI can surface *why* without knowing the SDK.
     */
    val lastError: String? get() = null

    /**
     * Plan a route from [origin] to [destination]. Returns null when unconfigured or the
     * request fails (no route, network error) — callers surface that as "can't route",
     * and can read [lastError] for the specific reason.
     */
    suspend fun plan(origin: GeoLocation, destination: GeoLocation): RoutePlan?
}
