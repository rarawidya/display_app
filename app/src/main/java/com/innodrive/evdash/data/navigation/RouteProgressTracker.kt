package com.innodrive.evdash.data.navigation

import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.NavProgress
import com.innodrive.evdash.domain.model.NavState
import com.innodrive.evdash.domain.model.RoutePlan

/**
 * Turns a static [RoutePlan] + a live GPS fix into a [NavProgress] snapshot — the
 * **provider-independent navigation model**. It imports no routing SDK: given any
 * plan (GraphHopper today, ORS/HERE later) it map-matches the fix onto the route,
 * advances the upcoming maneuver, and computes the distance-to-turn countdown,
 * distance/ETA remaining, and off-route state.
 *
 * `NavProgress` is the single source of truth: the same snapshot drives the Drive
 * cockpit map overlay and (via `RouteNavigator`) the BLE `NavInstruction` stream.
 *
 * Stateless per fix (recomputes from the plan each call) so it can't drift; the owning
 * [GraphHopperNavigationProvider] decides when an [NavState.OffRoute] result should
 * trigger a replan.
 */
class RouteProgressTracker(
    private val plan: RoutePlan,
    private val routeId: Long,
) {
    private val cum: DoubleArray = GeoMath.cumulativeDistances(plan.polyline)
    private val total: Double = if (cum.isNotEmpty()) cum.last() else plan.distanceMeters.toDouble()

    /** Distance-along-route (m) at which each maneuver occurs. */
    private val maneuverAlong: DoubleArray = DoubleArray(plan.maneuvers.size) { i ->
        val idx = plan.maneuvers[i].polylineIndex.coerceIn(0, (cum.size - 1).coerceAtLeast(0))
        cum.getOrElse(idx) { 0.0 }
    }

    fun onLocation(fix: GeoLocation): NavProgress {
        val snap = GeoMath.snapToPolyline(plan.polyline, cum, fix)
        val along = snap.alongMeters.coerceIn(0.0, total)
        val remaining = (total - along).coerceAtLeast(0.0)
        val speedKmh = ((fix.speedMps ?: 0f) * 3.6f).toInt().coerceAtLeast(0)

        val offRoute = snap.perpMeters > OFF_ROUTE_M
        val arrived = remaining <= ARRIVE_RADIUS_M

        // Upcoming maneuver = the first one still ahead of us along the route.
        var active = maneuverAlong.indexOfFirst { it > along + PASSED_EPS_M }
        if (active < 0) active = plan.maneuvers.lastIndex.coerceAtLeast(0)
        val activeMan = plan.maneuvers.getOrNull(active)
        val nextMan = plan.maneuvers.getOrNull(active + 1)

        val distanceToTurn = (maneuverAlong.getOrElse(active) { total } - along).coerceAtLeast(0.0)
        val nextDistance = if (nextMan != null) {
            (maneuverAlong[active + 1] - maneuverAlong[active]).coerceAtLeast(0.0)
        } else 0.0
        val eta = if (total > 0) (plan.durationSeconds * (remaining / total)).toInt() else 0

        val state = when {
            offRoute -> NavState.OffRoute
            arrived -> NavState.Arrived
            else -> NavState.Navigating
        }

        return NavProgress(
            routeId = routeId,
            state = state,
            maneuver = if (arrived) Maneuver.Arrive else (activeMan?.maneuver ?: Maneuver.ContinueStraight),
            roundaboutExit = activeMan?.roundaboutExit ?: 0,
            distanceToTurnM = distanceToTurn.toInt(),
            streetName = activeMan?.streetName ?: "",
            distanceRemainingM = remaining.toInt(),
            etaSeconds = eta,
            nextManeuver = nextMan?.maneuver ?: Maneuver.None,
            nextDistanceM = nextDistance.toInt(),
            speedKmh = speedKmh,
            totalDistanceM = plan.distanceMeters,
            totalDurationSec = plan.durationSeconds,
            destinationName = plan.destinationName,
            maneuverCount = plan.maneuvers.size,
        )
    }

    companion object {
        /** Perpendicular offset beyond which we consider the rider off-route. */
        const val OFF_ROUTE_M = 40.0
        /** Within this of the destination → arrived. */
        const val ARRIVE_RADIUS_M = 20.0
        private const val PASSED_EPS_M = 1.0
    }
}
