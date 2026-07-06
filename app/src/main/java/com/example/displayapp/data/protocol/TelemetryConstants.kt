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

    /**
     * Whether the wire `motorCurrentRaw` channel is calibrated. It is NOT in
     * protocol v1 — capnp.md marks it "TODO, currently sent 0", so current,
     * bus power (V×I), and the derived Wh/km + range are meaningless on real
     * hardware. While false, the Drive tiles render those as "—" instead of a
     * misleading 0. Flip to true once firmware defines the current scale + sign.
     *
     * Real-hardware default: `false`. Per the firmware BLE spec, `motorCurrentRaw`
     * is provisional and reads ~0 until road-calibrated, so current / power / Wh/km /
     * range must show "—" rather than a misleading 0. (Temporarily set true only for
     * simulator demos.)
     */
    const val CURRENT_CHANNEL_CALIBRATED: Boolean = false
}
