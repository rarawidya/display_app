package com.innodrive.evdash.domain.model

/**
 * A point on Earth + the time it was sampled.
 *
 * Framework-free (no `android.location.Location`) so the domain layer can be
 * unit-tested without instrumentation and so platform swaps stay isolated.
 *
 * @param latitude  degrees, -90..90
 * @param longitude degrees, -180..180
 * @param accuracyM horizontal accuracy in meters, or null if unknown
 * @param bearingDeg compass heading in degrees, or null if unknown
 * @param speedMps   ground speed in m/s, or null if unknown
 * @param timestampMs epoch milliseconds the fix was reported
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float? = null,
    val bearingDeg: Float? = null,
    val speedMps: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
