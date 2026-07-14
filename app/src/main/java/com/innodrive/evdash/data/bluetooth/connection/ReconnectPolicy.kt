package com.innodrive.evdash.data.bluetooth.connection

import timber.log.Timber

/**
 * Exponential backoff reconnection policy with jitter.
 *
 * Delays: 2s → 4s → 8s → 16s → 32s, then holds at 32s for the remaining attempts.
 * Jitter: ±25% randomization to avoid thundering herd if multiple devices reconnect simultaneously.
 *
 * `maxAttempts` is the self-heal window: a device that briefly drops out of range
 * should recover automatically, so we keep retrying at the 32s cap for several
 * minutes before conceding DISCONNECTED (previously only ~1 min / 5 attempts, which
 * gave up on an EV that was merely ignition-cycled or briefly out of range). A truly
 * absent device still stops eventually rather than draining the battery forever;
 * turning the adapter off short-circuits this immediately (see BleDataSource).
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 2000L,
    private val maxDelayMs: Long = 32000L,
    private val maxAttempts: Int = 12
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
