package com.example.displayapp.data.location

import com.example.displayapp.data.navigation.GeoMath
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A [LocationRepository] with no GPS — the "driver" walks the selected route.
 *
 * In simulator mode there is no real fix to plan from or to follow, so this source
 * synthesizes one:
 *  - **Idle** (no route): holds a fixed [startLocation]. That single non-null fix lets
 *    [com.example.displayapp.data.navigation.NavigationCoordinator.preview] plan a route
 *    (it needs an origin) and lets the map render the puck.
 *  - **Navigating**: once a destination is confirmed, [setRoute] is called with the
 *    active [RoutePlan]; this walks its `polyline` end to end, emitting interpolated
 *    fixes (with heading + speed) at [TICK_MS]. Everything downstream — the map puck,
 *    `RouteProgressTracker` → `NavProgress`, and the BLE `NavInstruction` stream — then
 *    advances exactly as it would on a real ride, with zero hardware.
 *
 * Pace comes from the live simulated vehicle speed ([speedKmh]) so the Drive gauge and
 * the map move together, floored at [MIN_MPS] so a red-light dip to ~0 never stalls the
 * drive forever. Distance/geometry math is shared with the real nav path via [GeoMath].
 */
class SimulatedLocationRepository(
    private val scope: CoroutineScope,
    /** Current simulated vehicle speed (km/h) — sampled each tick to pace the walk. */
    private val speedKmh: () -> Float,
    /** Where the puck idles before a route is picked (also the route-planning origin). */
    startLocation: GeoLocation = DEFAULT_START,
) : LocationRepository {

    private val _location = MutableStateFlow<GeoLocation?>(startLocation)
    override val location: Flow<GeoLocation?> = _location.asStateFlow()

    private var walkJob: Job? = null

    /**
     * Point the simulated driver at [plan] and walk it from origin to destination.
     * A new plan restarts the walk from its first vertex; `null` (route cancelled or
     * arrived) stops walking and holds the last emitted position.
     */
    fun setRoute(plan: RoutePlan?) {
        walkJob?.cancel()
        val poly = plan?.polyline?.takeIf { it.size >= 2 } ?: return
        walkJob = scope.launch { walk(poly) }
    }

    private suspend fun walk(poly: List<GeoLocation>) {
        val cum = GeoMath.cumulativeDistances(poly)
        val total = cum.last()
        Timber.tag(TAG).d("walking route: ${poly.size} pts, ${total.toInt()} m")
        var traveled = 0.0
        _location.value = pointAt(poly, cum, 0.0)
        while (currentCoroutineContext().isActive && traveled < total) {
            delay(TICK_MS)
            traveled += currentMps() * (TICK_MS / 1000.0)
            _location.value = pointAt(poly, cum, traveled)
        }
        // Land exactly on the destination so arrival detection fires cleanly.
        _location.value = pointAt(poly, cum, total)
        Timber.tag(TAG).d("route walk complete")
    }

    /** Ground speed to advance/emit, floored so the drive always makes progress. */
    private fun currentMps(): Double = (speedKmh() / 3.6).toDouble().coerceAtLeast(MIN_MPS)

    /** Interpolate the fix at [distance] metres along the route. */
    private fun pointAt(poly: List<GeoLocation>, cum: DoubleArray, distance: Double): GeoLocation {
        val d = distance.coerceIn(0.0, cum.last())
        var i = 0
        while (i < cum.size - 2 && cum[i + 1] < d) i++
        val a = poly[i]
        val b = poly[i + 1]
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 0.0) 0.0 else (d - cum[i]) / seg
        return GeoLocation(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
            accuracyM = SIM_ACCURACY_M,
            bearingDeg = bearing(a, b),
            speedMps = currentMps().toFloat(),
        )
    }

    /** Initial great-circle bearing a → b, degrees clockwise from north (0..360). */
    private fun bearing(a: GeoLocation, b: GeoLocation): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    companion object {
        private const val TAG = "SimLocation"
        private const val TICK_MS = 200L      // 5 Hz — smooth puck + nav progress
        private const val MIN_MPS = 2.5       // ~9 km/h floor so a stop never stalls the walk
        private const val SIM_ACCURACY_M = 3f
        /** Central Jakarta — matches the nav-demo origin in AppContainer. */
        val DEFAULT_START = GeoLocation(latitude = -6.2088, longitude = 106.8456)
    }
}
