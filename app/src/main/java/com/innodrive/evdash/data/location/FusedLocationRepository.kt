package com.innodrive.evdash.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.repository.LocationRepository
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [LocationRepository] backed by Play Services' [FusedLocationProviderClient].
 *
 * Architecture:
 * - The flow seeds with `null` (so consumers can render the "no fix yet" UI)
 *   then emits each fresh fix as the provider supplies it.
 * - If the runtime permission is missing, we emit `null` and end — no
 *   SecurityException leaks to the UI.
 * - Lifecycle-safe: `awaitClose` unregisters the callback when the collecting
 *   coroutine scope is cancelled (e.g. when the screen leaves the back stack).
 *
 * Cadence is set to 2 s with a 500 ms fastest interval — enough to track
 * vehicle movement smoothly without burning power on stale fixes.
 */
class FusedLocationRepository(
    private val context: Context
) : LocationRepository {

    @SuppressLint("MissingPermission")
    override val location: Flow<GeoLocation?> = callbackFlow {
        if (!hasLocationPermission()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()

        // Seed an initial null so the UI doesn't sit empty waiting for a fix
        trySend(null)

        // Try the last known fix immediately so the map can frame approximately
        client.lastLocation.addOnSuccessListener { loc ->
            loc?.let { trySend(it.toGeoLocation()) }
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toGeoLocation()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun android.location.Location.toGeoLocation() = GeoLocation(
        latitude    = latitude,
        longitude   = longitude,
        accuracyM   = if (hasAccuracy()) accuracy else null,
        bearingDeg  = if (hasBearing())  bearing  else null,
        speedMps    = if (hasSpeed())    speed    else null,
        timestampMs = time
    )
}
