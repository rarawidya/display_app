package com.innodrive.evdash.data.navigation.graphhopper

import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.RouteManeuver
import com.innodrive.evdash.domain.model.RoutePlan
import com.innodrive.evdash.domain.repository.RoutePlanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * [RoutePlanner] backed by the **GraphHopper hosted Directions API**
 * (`graphhopper.com/api/1/route`). It is the ONLY routing-SDK-specific class: it makes
 * the request and maps GraphHopper `sign` codes onto the domain [Maneuver] enum. The
 * resulting [RoutePlan] is provider-neutral, so `RouteProgressTracker`, the map overlay,
 * and the BLE stream never see GraphHopper.
 *
 * Swapping to a self-hosted GraphHopper server = pass a different [baseUrl]; swapping to
 * ORS/HERE = a different [RoutePlanner] implementation, with nothing downstream changed.
 *
 * Zero new dependencies: `HttpURLConnection` + `org.json` (both in the Android SDK).
 * [apiKey] comes from `local.properties` (gitignored) via BuildConfig; blank →
 * [isConfigured] is false and [plan] returns null (callers show "can't route").
 */
class GraphHopperRoutePlanner(
    private val apiKey: String,
    // "car" is available on every GraphHopper plan (incl. free); "scooter"/"bike"/etc.
    // require a plan that enables them — an unavailable profile returns HTTP 400.
    private val profile: String = "car",
    private val baseUrl: String = "https://graphhopper.com/api/1",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RoutePlanner {

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    @Volatile
    private var lastErrorMessage: String? = null
    override val lastError: String? get() = lastErrorMessage

    override suspend fun plan(origin: GeoLocation, destination: GeoLocation): RoutePlan? {
        if (!isConfigured) return null
        return withContext(io) {
            lastErrorMessage = null
            try {
                request(origin, destination)
            } catch (e: GraphHopperException) {
                lastErrorMessage = e.error.userMessage
                Timber.w("GraphHopper route failed: ${e.error.userMessage}")
                null
            } catch (e: Exception) {
                lastErrorMessage = GraphHopperError.Unknown(e.message).userMessage
                Timber.w(e, "GraphHopper route request failed")
                null
            }
        }
    }

    private suspend fun request(origin: GeoLocation, destination: GeoLocation): RoutePlan? {
        val url = buildString {
            append("$baseUrl/route")
            append("?point=${origin.latitude},${origin.longitude}")
            append("&point=${destination.latitude},${destination.longitude}")
            append("&profile=$profile&locale=en&points_encoded=false&instructions=true")
            append("&key=$apiKey")
        }
        val body = GraphHopperHttp.getJson(url, connectTimeoutMs = 10_000, readTimeoutMs = 15_000)
        return parse(origin, destination, body)
    }

    private fun parse(origin: GeoLocation, destination: GeoLocation, json: String): RoutePlan? {
        val paths = JSONObject(json).optJSONArray("paths") ?: return null
        if (paths.length() == 0) return null
        val path = paths.getJSONObject(0)

        // points_encoded=false → GeoJSON LineString: coordinates are [lng, lat].
        val coords = path.getJSONObject("points").getJSONArray("coordinates")
        val polyline = ArrayList<GeoLocation>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            polyline.add(GeoLocation(latitude = c.getDouble(1), longitude = c.getDouble(0)))
        }
        if (polyline.size < 2) return null

        val instructions = path.optJSONArray("instructions") ?: JSONArray()
        val maneuvers = ArrayList<RouteManeuver>(instructions.length())
        for (i in 0 until instructions.length()) {
            val ins = instructions.getJSONObject(i)
            val startIdx = ins.optJSONArray("interval")?.optInt(0, 0) ?: 0
            maneuvers.add(
                RouteManeuver(
                    // First instruction is the departure; the rest map by sign.
                    maneuver = if (i == 0) Maneuver.Depart else signToManeuver(ins.optInt("sign", 0)),
                    location = polyline.getOrElse(startIdx) { polyline.first() },
                    // The road you turn ONTO only — never the instruction "text"
                    // (a maneuver sentence like "Turn right"): the board prints the
                    // maneuver from the enum already, so a text fallback renders as
                    // "Turn right - Turn right". Unnamed roads stay blank.
                    streetName = ins.optString("street_name"),
                    distanceMeters = ins.optDouble("distance", 0.0).toInt(),
                    roundaboutExit = ins.optInt("exit_number", 0),
                    polylineIndex = startIdx,
                ),
            )
        }

        return RoutePlan(
            origin = origin,
            destination = destination,
            polyline = polyline,
            maneuvers = maneuvers,
            distanceMeters = path.optDouble("distance", 0.0).toInt(),
            durationSeconds = (path.optLong("time", 0L) / 1000L).toInt(),
        )
    }

    /** GraphHopper instruction `sign` → domain [Maneuver]. */
    private fun signToManeuver(sign: Int): Maneuver = when (sign) {
        -98, -8, 8 -> Maneuver.UTurn // U_TURN_UNKNOWN / U_TURN_LEFT / U_TURN_RIGHT
        -7 -> Maneuver.KeepLeft
        -3 -> Maneuver.TurnSharpLeft
        -2 -> Maneuver.TurnLeft
        -1 -> Maneuver.TurnSlightLeft
        0 -> Maneuver.ContinueStraight
        1 -> Maneuver.TurnSlightRight
        2 -> Maneuver.TurnRight
        3 -> Maneuver.TurnSharpRight
        4 -> Maneuver.Arrive        // finish
        5 -> Maneuver.ContinueStraight // via waypoint reached
        6 -> Maneuver.Roundabout    // pair with exit_number
        7 -> Maneuver.KeepRight
        else -> Maneuver.ContinueStraight
    }
}
