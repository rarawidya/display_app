package com.example.displayapp.data.energy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure trapezoidal energy integrator.
 *
 * These lock down the exact Wh math (drives per-trip energy billing + regen
 * accounting), the first-sample baseline, the [EnergyAccumulator.MAX_DT_MS]
 * dropout clamp, and out-of-order rejection. All deterministic — no clock reads.
 */
class EnergyAccumulatorTest {

    private val acc = EnergyAccumulator()

    /** Feed a constant-power stream at 1 Hz for `seconds` seconds. */
    private fun feedConstant(voltage: Float, current: Float, seconds: Int) {
        for (s in 0..seconds) acc.onSample(voltage, current, s * 1000L)
    }

    @Test
    fun `first sample only establishes baseline`() {
        acc.onSample(100f, 10f, 0L)
        assertEquals(0.0, acc.usedWh(), 0.0)
        assertEquals(0.0, acc.regenWh(), 0.0)
    }

    @Test
    fun `constant discharge integrates to power times hours`() {
        // 1000 W for one hour = 1000 Wh, trapezoidal-exact for constant power.
        feedConstant(100f, 10f, 3600)
        assertEquals(1000.0, acc.usedWh(), 1e-6)
        assertEquals(0.0, acc.regenWh(), 0.0)
        assertEquals(1000.0, acc.netWh(), 1e-6)
    }

    @Test
    fun `constant regen accrues to regen not used`() {
        // Negative current = regen. 1000 W recovered for one hour = 1000 Wh regen.
        feedConstant(100f, -10f, 3600)
        assertEquals(0.0, acc.usedWh(), 0.0)
        assertEquals(1000.0, acc.regenWh(), 1e-6)
        assertEquals(-1000.0, acc.netWh(), 1e-6)
    }

    @Test
    fun `dt is clamped so a dropout does not fabricate energy`() {
        // Baseline, then a 10 s gap at 1000 W. Clamp bounds it to 1 s of billing.
        acc.onSample(100f, 10f, 0L)
        acc.onSample(100f, 10f, 10_000L)
        val oneSecondWh = 1000.0 * EnergyAccumulator.MAX_DT_MS / 3_600_000.0
        assertEquals(oneSecondWh, acc.usedWh(), 1e-9)
    }

    @Test
    fun `out-of-order and duplicate timestamps are ignored`() {
        acc.onSample(100f, 10f, 1000L)   // baseline
        acc.onSample(100f, 10f, 500L)    // out of order → ignored
        acc.onSample(100f, 10f, 1000L)   // duplicate → ignored
        assertEquals(0.0, acc.usedWh(), 0.0)
        // A valid forward step now integrates from the baseline.
        acc.onSample(100f, 10f, 2000L)
        assertEquals(1000.0 * 1000 / 3_600_000.0, acc.usedWh(), 1e-9)
    }

    @Test
    fun `reset clears all accumulators and the baseline`() {
        feedConstant(100f, 10f, 10)
        acc.reset()
        assertEquals(0.0, acc.usedWh(), 0.0)
        assertEquals(0.0, acc.regenWh(), 0.0)
        // After reset the next sample is a fresh baseline (integrates nothing).
        acc.onSample(100f, 10f, 5000L)
        assertEquals(0.0, acc.usedWh(), 0.0)
    }

    @Test
    fun `trapezoid averages the two endpoint powers`() {
        // Ramp 0 W → 2000 W over 1 s: area = avg(0,2000) × 1 s = 1000 W·s.
        acc.onSample(100f, 0f, 0L)
        acc.onSample(100f, 20f, 1000L)
        assertEquals(1000.0 / 3600.0, acc.usedWh(), 1e-9)
    }
}
