package com.innodrive.evdash.data.navigation.graphhopper

import kotlinx.coroutines.delay
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A classified GraphHopper failure. Carries a short, user-facing [userMessage]
 * so the UI can say *why* routing/search failed instead of a blanket
 * "can't route" — a bad key, an exhausted quota, and a dropped connection are
 * very different problems for the user.
 */
internal sealed class GraphHopperError(val userMessage: String) {
    /** No/'dropped connection or timeout — worth retrying. */
    object Network : GraphHopperError("No connection. Check your network and try again.")

    /** HTTP 429 — quota/rate limit; a short backoff often clears it. */
    object RateLimited : GraphHopperError("Too many requests — try again in a moment.")

    /** HTTP 401/403 — the API key is missing/invalid. Won't fix itself. */
    object Unauthorized : GraphHopperError("Routing key rejected — check GRAPHHOPPER_API_KEY.")

    /** HTTP 400 — bad request (e.g. a profile the plan doesn't allow). Server message if any. */
    class BadRequest(serverMessage: String?) :
        GraphHopperError(serverMessage?.ifBlank { null } ?: "Route request not supported.")

    /** HTTP 5xx — the service is down/unstable; worth retrying. */
    object Server : GraphHopperError("Routing service is temporarily unavailable.")

    /** Anything else. */
    class Unknown(serverMessage: String?) :
        GraphHopperError(serverMessage?.ifBlank { null } ?: "Something went wrong. Try again.")

    /** Transient errors are safe to retry; auth/bad-request are not. */
    val transient: Boolean get() = this is Network || this is RateLimited || this is Server
}

/** Thrown by [GraphHopperHttp] so callers can classify + surface the reason. */
internal class GraphHopperException(val error: GraphHopperError) : Exception(error.userMessage)

/**
 * Shared GET helper for the GraphHopper hosted APIs. Adds the robustness the two
 * ports need over raw [HttpURLConnection]:
 *  - **reads the error body** on a non-2xx response (GraphHopper returns a JSON
 *    `message`) and drains it so the connection can be reused,
 *  - **classifies** the status into a typed [GraphHopperError],
 *  - **retries transient failures** (network/timeout, 429, 5xx) with a bounded
 *    backoff — a single dropped request no longer surfaces as "can't route".
 *
 * Zero new dependencies (`HttpURLConnection` + `org.json`, both in the SDK).
 */
internal object GraphHopperHttp {

    const val MAX_ATTEMPTS = 3

    /** Backoff before attempt N (index 0 = first try, no wait). */
    private val BACKOFF_MS = longArrayOf(0L, 300L, 900L)

    /** GET [url] and return the response body, or throw [GraphHopperException]. */
    suspend fun getJson(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String =
        withRetry { execute(url, connectTimeoutMs, readTimeoutMs) }

    /**
     * Retry driver, extracted so the fail-fast/backoff policy is unit-testable
     * without a network. Retries only [GraphHopperError.transient] failures.
     */
    suspend fun <T> withRetry(fetch: suspend () -> T): T {
        var last: GraphHopperException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) delay(BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() })
            try {
                return fetch()
            } catch (e: GraphHopperException) {
                last = e
                if (!e.error.transient) throw e
                Timber.w("GraphHopper transient failure (attempt ${attempt + 1}/$MAX_ATTEMPTS): ${e.error.userMessage}")
            }
        }
        throw last ?: GraphHopperException(GraphHopperError.Unknown(null))
    }

    /** Map an HTTP status + optional server message to a typed error. Pure. */
    fun classify(code: Int, serverMessage: String?): GraphHopperError = when (code) {
        401, 403 -> GraphHopperError.Unauthorized
        429 -> GraphHopperError.RateLimited
        400 -> GraphHopperError.BadRequest(serverMessage)
        in 500..599 -> GraphHopperError.Server
        else -> GraphHopperError.Unknown(serverMessage)
    }

    private fun execute(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
        try {
            val code = conn.responseCode // throws IOException on connection failure
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            // Drain + parse the error body (GraphHopper returns {"message": "..."});
            // draining also lets the socket be reused rather than abandoned.
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val serverMessage = runCatching { JSONObject(body).optString("message").ifBlank { null } }.getOrNull()
            Timber.w("GraphHopper HTTP $code: ${serverMessage ?: body.take(200)}")
            throw GraphHopperException(classify(code, serverMessage))
        } catch (e: IOException) {
            throw GraphHopperException(GraphHopperError.Network)
        } finally {
            conn.disconnect()
        }
    }
}
