package com.innodrive.evdash.data.protocol

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

    /**
     * Whether the wire `currentMotor` channel is calibrated.
     *
     * As of the firmware capnp.md revision (2026-07), `currentMotor` (@1) is
     * **CONFIRMED** signed deci-amps (÷10 = A, negative = regen) — matched
     * against the vendor display. So current, bus power (V×I), and the derived
     * Wh/km + range are trustworthy on real hardware; the Drive/Charts surfaces
     * render them as live values rather than "—".
     *
     * Kept as a flag (rather than deleted) so the "—" fallback stays one edit
     * away if a future wire revision de-calibrates the channel again.
     */
    const val CURRENT_CHANNEL_CALIBRATED: Boolean = true
}
