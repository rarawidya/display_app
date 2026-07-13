package com.example.displayapp.data.navigation.graphhopper

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.GeoPlace
import com.example.displayapp.domain.repository.Geocoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
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

    @Volatile
    private var lastErrorMessage: String? = null
    override val lastError: String? get() = lastErrorMessage

    override suspend fun search(query: String, near: GeoLocation?): List<GeoPlace> {
        if (!isConfigured || query.isBlank()) return emptyList()
        val url = buildString {
            append("$baseUrl/geocode?q=${URLEncoder.encode(query, "UTF-8")}")
            append("&limit=6&locale=en")
            if (near != null) append("&point=${near.latitude},${near.longitude}")
            append("&key=$apiKey")
        }
        return withContext(io) { runCatchingGeocode("geocode") { fetch(url) } ?: emptyList() }
    }

    override suspend fun reverse(location: GeoLocation): GeoPlace? {
        if (!isConfigured) return null
        val url = "$baseUrl/geocode?reverse=true&point=${location.latitude},${location.longitude}" +
            "&limit=1&locale=en&key=$apiKey"
        return withContext(io) { runCatchingGeocode("reverse geocode") { fetch(url).firstOrNull() } }
    }

    /** Run a geocode request, classifying + recording any error into [lastError]. */
    private inline fun <T> runCatchingGeocode(what: String, block: () -> T): T? {
        lastErrorMessage = null
        return try {
            block()
        } catch (e: GraphHopperException) {
            lastErrorMessage = e.error.userMessage
            Timber.w("GraphHopper $what failed: ${e.error.userMessage}")
            null
        } catch (e: Exception) {
            lastErrorMessage = GraphHopperError.Unknown(e.message).userMessage
            Timber.w(e, "GraphHopper $what failed")
            null
        }
    }

    private suspend fun fetch(url: String): List<GeoPlace> =
        parse(GraphHopperHttp.getJson(url, connectTimeoutMs = 8_000, readTimeoutMs = 10_000))

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
