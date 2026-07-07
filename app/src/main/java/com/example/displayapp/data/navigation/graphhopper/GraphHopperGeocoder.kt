package com.example.displayapp.data.navigation.graphhopper

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.GeoPlace
import com.example.displayapp.domain.repository.Geocoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [Geocoder] backed by the **GraphHopper Geocoding API** (`/api/1/geocode`) — the same
 * hosted service + API key as routing, so place search "just works" for real addresses/
 * POIs instead of the old hardcoded demo list. Results are provider-neutral [GeoPlace]s.
 *
 * Zero new dependencies (`HttpURLConnection` + `org.json`). Swap this class for
 * ORS/MapTiler/Nominatim with nothing else changing.
 */
class GraphHopperGeocoder(
    private val apiKey: String,
    private val baseUrl: String = "https://graphhopper.com/api/1",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Geocoder {

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    override suspend fun search(query: String, near: GeoLocation?): List<GeoPlace> {
        if (!isConfigured || query.isBlank()) return emptyList()
        return withContext(io) {
            runCatching { request(query, near) }
                .onFailure { Timber.w(it, "GraphHopper geocode failed") }
                .getOrDefault(emptyList())
        }
    }

    private fun request(query: String, near: GeoLocation?): List<GeoPlace> {
        val url = buildString {
            append("$baseUrl/geocode?q=${URLEncoder.encode(query, "UTF-8")}")
            append("&limit=6&locale=en")
            if (near != null) append("&point=${near.latitude},${near.longitude}")
            append("&key=$apiKey")
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Timber.w("GraphHopper geocode HTTP ${conn.responseCode}")
                emptyList()
            } else {
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<GeoPlace> {
        val hits = JSONObject(json).optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<GeoPlace>(hits.length())
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            val point = h.optJSONObject("point") ?: continue
            val name = h.optString("name").ifBlank { continue }
            val detail = listOf(
                h.optString("street"),
                h.optString("city"),
                h.optString("state"),
                h.optString("country"),
            ).filter { it.isNotBlank() }.distinct().joinToString(", ")
            out.add(
                GeoPlace(
                    name = name,
                    detail = detail,
                    location = GeoLocation(
                        latitude = point.getDouble("lat"),
                        longitude = point.getDouble("lng"),
                    ),
                ),
            )
        }
        return out
    }
}
