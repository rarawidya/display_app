package com.example.displayapp.data.energy

import kotlin.math.absoluteValue

/**
 * Pure Kotlin energy integrator. Accumulates Wh from a stream of
 * (voltage, current, timestamp) samples using trapezoidal integration.
 *
 * Sign convention (matches the rest of the codebase):
 *   - positive current = discharge (energy leaving the battery)  → counts toward [usedWh]
 *   - negative current = regen     (energy entering the battery) → counts toward [regenWh]
 *
 * Robustness:
 *   - The first sample only establishes the baseline; nothing is integrated until
 *     the second sample arrives.
 *   - dt is clamped to [MAX_DT_MS]. A BT dropout that pauses sample arrival for
 *     10 s would otherwise be billed as 10 s of integration at the last known
 *     power — the clamp limits that error to 1 s.
 *   - Zero-dt samples (same timestamp twice) are ignored.
 *
 * Determinism: no clock reads, no random sources. Two identical input streams
 * always produce identical totals — trivially unit-testable.
 */
class EnergyAccumulator {

    private var lastTimestampMs: Long = -1L
    private var lastPowerW: Double = 0.0
    private var usedWh: Double = 0.0
    private var regenWh: Double = 0.0

    /** Total energy discharged in Wh (always ≥ 0). */
    fun usedWh(): Double = usedWh

    /** Total energy recovered through regen in Wh (always ≥ 0). */
    fun regenWh(): Double = regenWh

    /** Net Wh = used − regen. Negative is theoretically possible after a long downhill. */
    fun netWh(): Double = usedWh - regenWh

    /**
     * Feed one telemetry sample. Order matters — samples must be in monotonic
     * timestamp order. Out-of-order timestamps are silently ignored.
     */
    fun onSample(voltage: Float, current: Float, timestampMs: Long) {
        val powerW = voltage.toDouble() * current.toDouble()

        if (lastTimestampMs < 0L) {
            // First sample — establish baseline, integrate nothing.
            lastTimestampMs = timestampMs
            lastPowerW = powerW
            return
        }

        val dtMsRaw = timestampMs - lastTimestampMs
        if (dtMsRaw <= 0L) return  // out-of-order or duplicate timestamp

        val dtMs = if (dtMsRaw > MAX_DT_MS) MAX_DT_MS else dtMsRaw

        // Trapezoidal area between two power samples. Wh = W × hours.
        val avgPowerW = (lastPowerW + powerW) / 2.0
        val deltaWh = avgPowerW * dtMs / MS_PER_HOUR

        if (deltaWh >= 0.0) usedWh += deltaWh
        else regenWh += deltaWh.absoluteValue

        lastTimestampMs = timestampMs
        lastPowerW = powerW
    }

    /** Reset to a fresh accumulator. Cheaper than constructing a new one. */
    fun reset() {
        lastTimestampMs = -1L
        lastPowerW = 0.0
        usedWh = 0.0
        regenWh = 0.0
    }

    companion object {
        /** Max dt counted per gap. Anything longer is clamped (assumed BT dropout). */
        const val MAX_DT_MS: Long = 1_000L
        private const val MS_PER_HOUR: Double = 3_600_000.0
    }
}
