package com.example.displayapp.data.navigation

import com.example.displayapp.domain.model.GeoLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, dependency-free geo helpers for route map-matching. Pure functions — used by
 * the provider-independent [RouteProgressTracker] and unit-testable without Android.
 */
internal object GeoMath {

    private const val EARTH_R = 6_371_000.0
    private const val METERS_PER_DEG = EARTH_R * Math.PI / 180.0

    /** Great-circle distance in metres. */
    fun distanceMeters(a: GeoLocation, b: GeoLocation): Double {
        val dLat = (b.latitude - a.latitude) * Math.PI / 180
        val dLng = (b.longitude - a.longitude) * Math.PI / 180
        val s1 = sin(dLat / 2)
        val s2 = sin(dLng / 2)
        val h = s1 * s1 + cos(a.latitude * Math.PI / 180) * cos(b.latitude * Math.PI / 180) * s2 * s2
        return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Cumulative distance (metres) at each vertex of [polyline]; `[0]` = 0. */
    fun cumulativeDistances(polyline: List<GeoLocation>): DoubleArray {
        val cum = DoubleArray(polyline.size)
        for (i in 1 until polyline.size) {
            cum[i] = cum[i - 1] + distanceMeters(polyline[i - 1], polyline[i])
        }
        return cum
    }

    /** Snap [p] to the polyline: nearest segment's along-route distance + perpendicular offset. */
    data class Snap(val alongMeters: Double, val perpMeters: Double)

    fun snapToPolyline(polyline: List<GeoLocation>, cum: DoubleArray, p: GeoLocation): Snap {
        if (polyline.size < 2) return Snap(0.0, 0.0)
        var best = Snap(0.0, Double.MAX_VALUE)
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            // Local equirectangular projection around segment start (accurate for short segments).
            val scaleLng = cos(a.latitude * Math.PI / 180) * METERS_PER_DEG
            val bx = (b.longitude - a.longitude) * scaleLng
            val by = (b.latitude - a.latitude) * METERS_PER_DEG
            val px = (p.longitude - a.longitude) * scaleLng
            val py = (p.latitude - a.latitude) * METERS_PER_DEG
            val segLen2 = bx * bx + by * by
            val t = if (segLen2 <= 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
            val projX = t * bx
            val projY = t * by
            val perp = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
            if (perp < best.perpMeters) {
                val along = cum[i] + t * sqrt(segLen2)
                best = Snap(alongMeters = along, perpMeters = perp)
            }
        }
        return best
    }
}
