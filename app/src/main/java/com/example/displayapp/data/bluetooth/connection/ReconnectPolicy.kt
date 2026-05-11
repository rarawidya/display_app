package com.example.displayapp.data.bluetooth.connection

import timber.log.Timber

/**
 * Exponential backoff reconnection policy with jitter.
 *
 * Delays: 2s → 4s → 8s → 16s → 32s (capped)
 * Jitter: ±25% randomization to avoid thundering herd if multiple devices reconnect simultaneously.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 2000L,
    private val maxDelayMs: Long = 32000L,
    private val maxAttempts: Int = 5
) {
    private var attempt = 0

    val canRetry: Boolean get() = attempt < maxAttempts
    val currentAttempt: Int get() = attempt

    fun nextDelayMs(): Long {
        val exponentialDelay = (baseDelayMs * (1L shl attempt.coerceAtMost(4)))
            .coerceAtMost(maxDelayMs)
        // Add ±25% jitter
        val jitter = (exponentialDelay * 0.25 * (Math.random() * 2 - 1)).toLong()
        val delay = (exponentialDelay + jitter).coerceAtLeast(baseDelayMs)
        attempt++
        Timber.d("Reconnect attempt $attempt, delay: ${delay}ms")
        return delay
    }

    fun reset() {
        attempt = 0
    }
}
