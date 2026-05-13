package com.example.displayapp.data.protocol

/**
 * Shared timing constants for sample-to-sample telemetry math.
 *
 * Phase 1 audit invariant: every integrator (energy, distance, efficiency)
 * and the live-session UI clamp `dt` between consecutive samples to the
 * **same** value, so a 1.2-second BT pause never counts toward one
 * surface's distance but not another's.
 *
 * The 1-second clamp is calibrated against the canonical 20 Hz wire rate:
 * any gap longer than ~20 missed frames is almost certainly a BT dropout or
 * a backgrounded process, not real telemetry — clamping bounds the error
 * to a single second of integration at the last known sample.
 */
object TelemetryConstants {

    /**
     * Max `dt` (ms) counted toward integration when accumulating energy,
     * distance, or rolling efficiency between two samples. Anything larger
     * is treated as a dropout and clamped.
     */
    const val MAX_SAMPLE_DT_MS: Long = 1_000L

    /**
     * Max wait between replay emissions. Distinct semantic from
     * [MAX_SAMPLE_DT_MS]: this only bounds UI responsiveness when a recorded
     * trip happens to contain a long gap; it does NOT affect any analytics
     * the integrators recompute on those samples.
     */
    const val MAX_REPLAY_DELAY_MS: Long = 2_000L
}
