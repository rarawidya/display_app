package com.example.displayapp.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.RoutePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

// Own DataStore file: the polyline is bulky next to the scalar app prefs, and
// "reset app preferences" must not wipe the last-route thumbnail (or vice versa).
private val Context.lastRouteDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_route")

/**
 * Persists the most recent **navigated route** so the Home page's Last Ride card
 * can draw the real route shape in its map thumbnail (trips don't record GPS —
 * the navigation session is the only source of route geometry).
 *
 * Saved by [com.example.displayapp.data.navigation.NavigationCoordinator] every
 * time a session activates a route (start or reroute — latest wins), downsampled
 * to [MAX_POINTS] and encoded as a compact `lat,lon|lat,lon|…` string (1e-5 deg
 * ≈ 1.1 m — thumbnail precision, ~1 KB).
 */
class LastRouteStore(private val context: Context) {

    /** A stored route summary: the downsampled polyline + when it was saved. */
    data class StoredRoute(
        val points: List<GeoLocation>,
        val destinationName: String,
        val savedAtMs: Long,
    )

    /** Last navigated route; null until the first navigation session. */
    val route: Flow<StoredRoute?> = context.lastRouteDataStore.data.map { p ->
        val points = decode(p[KEY_POLYLINE].orEmpty())
        if (points.size < 2) null
        else StoredRoute(
            points = points,
            destinationName = p[KEY_DESTINATION].orEmpty(),
            savedAtMs = p[KEY_SAVED_AT] ?: 0L,
        )
    }

    suspend fun save(plan: RoutePlan, destinationName: String = plan.destinationName) {
        val encoded = encode(downsample(plan.polyline))
        if (encoded.isEmpty()) return
        context.lastRouteDataStore.edit {
            it[KEY_POLYLINE] = encoded
            it[KEY_DESTINATION] = destinationName
            it[KEY_SAVED_AT] = System.currentTimeMillis()
        }
    }

    private fun downsample(pts: List<GeoLocation>): List<GeoLocation> {
        if (pts.size <= MAX_POINTS) return pts
        val step = pts.size.toDouble() / MAX_POINTS
        val out = ArrayList<GeoLocation>(MAX_POINTS + 1)
        var i = 0.0
        while (i < pts.size) { out.add(pts[i.toInt()]); i += step }
        if (out.last() !== pts.last()) out.add(pts.last())
        return out
    }

    private fun encode(pts: List<GeoLocation>): String =
        if (pts.size < 2) "" else pts.joinToString("|") {
            String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude)
        }

    private fun decode(raw: String): List<GeoLocation> =
        raw.split('|').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoLocation(latitude = lat, longitude = lon)
        }

    private companion object {
        /** Thumbnail resolution — the card canvas is ~80 dp wide. */
        const val MAX_POINTS = 48
        val KEY_POLYLINE = stringPreferencesKey("polyline")
        val KEY_DESTINATION = stringPreferencesKey("destination")
        val KEY_SAVED_AT = longPreferencesKey("saved_at_ms")
    }
}
