package com.innodrive.evdash.data.navigation.graphhopper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the pure classification + retry policy that make GraphHopper
 * robust: transient failures (network / 429 / 5xx) retry, auth / bad-request
 * fail fast, and each status maps to a distinct user-facing reason.
 */
class GraphHopperHttpTest {

    @Test
    fun `status codes classify to the right typed error`() {
        assertTrue(GraphHopperHttp.classify(401, null) is GraphHopperError.Unauthorized)
        assertTrue(GraphHopperHttp.classify(403, null) is GraphHopperError.Unauthorized)
        assertTrue(GraphHopperHttp.classify(429, null) is GraphHopperError.RateLimited)
        assertTrue(GraphHopperHttp.classify(400, "bad profile") is GraphHopperError.BadRequest)
        assertTrue(GraphHopperHttp.classify(500, null) is GraphHopperError.Server)
        assertTrue(GraphHopperHttp.classify(503, null) is GraphHopperError.Server)
        assertTrue(GraphHopperHttp.classify(418, null) is GraphHopperError.Unknown)
    }

    @Test
    fun `bad request surfaces the server message when present`() {
        val e = GraphHopperHttp.classify(400, "Vehicle profile 'scooter' not supported")
        assertTrue(e.userMessage.contains("scooter"))
        // …and falls back to a generic message when the server said nothing.
        assertTrue(GraphHopperHttp.classify(400, null).userMessage.isNotBlank())
    }

    @Test
    fun `only network, rate-limit and server errors are transient`() {
        assertTrue(GraphHopperError.Network.transient)
        assertTrue(GraphHopperError.RateLimited.transient)
        assertTrue(GraphHopperError.Server.transient)
        assertFalse(GraphHopperError.Unauthorized.transient)
        assertFalse(GraphHopperError.BadRequest(null).transient)
        assertFalse(GraphHopperError.Unknown(null).transient)
    }

    @Test
    fun `withRetry returns immediately on success`() = runBlocking {
        var calls = 0
        val result = GraphHopperHttp.withRetry { calls++; "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `withRetry recovers a transient failure then succeeds`() = runBlocking {
        var calls = 0
        val result = GraphHopperHttp.withRetry {
            calls++
            if (calls < 2) throw GraphHopperException(GraphHopperError.Network)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `withRetry exhausts attempts on a persistent transient failure`() = runBlocking {
        var calls = 0
        try {
            GraphHopperHttp.withRetry<String> {
                calls++; throw GraphHopperException(GraphHopperError.Server)
            }
            fail("expected GraphHopperException")
        } catch (e: GraphHopperException) {
            assertTrue(e.error is GraphHopperError.Server)
        }
        assertEquals(GraphHopperHttp.MAX_ATTEMPTS, calls)
    }

    @Test
    fun `withRetry fails fast on a non-transient failure`() = runBlocking {
        var calls = 0
        try {
            GraphHopperHttp.withRetry<String> {
                calls++; throw GraphHopperException(GraphHopperError.Unauthorized)
            }
            fail("expected GraphHopperException")
        } catch (e: GraphHopperException) {
            assertTrue(e.error is GraphHopperError.Unauthorized)
        }
        assertEquals(1, calls) // no retry
    }
}
