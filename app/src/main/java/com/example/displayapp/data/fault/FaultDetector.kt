package com.example.displayapp.data.fault

import com.example.displayapp.domain.model.VehicleData

/**
 * A fault/warning condition that just became active on the telemetry stream.
 *
 * `severity` mirrors [com.example.displayapp.data.persistence.entity.FaultEventEntity]:
 * 0 = info, 1 = warning, 2 = critical.
 */
data class DetectedFault(
    val type: String,
    val severity: Int,
    val message: String
)

/**
 * Turns the live telemetry stream into discrete fault events.
 *
 * **Edge-triggered, not level-triggered** — feeding a frame that stays in a
 * fault condition emits the event exactly *once* (on the rising edge) rather
 * than on every ~10 Hz frame, so the fault log records "engine overheated"
 * as one row, not three hundred. A condition re-arms (can fire again) only
 * after the reading recovers past a hysteresis margin, so a value hovering on
 * a threshold doesn't flap.
 *
 * **Pure and deterministic** — no clock reads, no I/O. The caller stamps each
 * returned fault with the sample timestamp and persists it, which keeps this
 * class trivially unit-testable and keeps a wire revision from forcing changes
 * here.
 *
 * ### What it detects today
 * - **CONTROLLER_FAULT** — `faultCode` transitions to non-zero. The Votol
 *   `faultCode` bitfield is PROVISIONAL (firmware sends 0 today, see
 *   `docs/capnpble.md`), so the raw code is logged as hex; when firmware
 *   defines the bits, decode them into named faults here without touching the
 *   wiring or the UI.
 * - **BATT_LOW** — the wire `flags` low-battery bit (bit6, SoC ≤ 15%, a
 *   firmware-CONFIRMED signal) rises. Keyed off the flag rather than a local
 *   SoC threshold so it matches exactly what the cluster shows.
 * - **MOTOR_TEMP / BATTERY_TEMP / CONTROLLER_TEMP** — the respective temp
 *   channel crosses the warning (≥50 °C) or critical (≥70 °C) band. Thresholds
 *   mirror `temperatureAlertLevel` in the dashboard state (kept local so the
 *   data layer doesn't depend on presentation). Battery-pack temp is only
 *   evaluated when `batteryTempAvailable`.
 */
class FaultDetector {

    // Highest currently-active temp band per channel (0 normal, 1 warn, 2 crit).
    private var motorTempLevel = 0
    private var batteryTempLevel = 0
    private var controllerTempLevel = 0
    // Boolean latches for the on/off conditions.
    private var faultCodeArmed = false
    private var lowBatteryArmed = false

    /**
     * Feed one telemetry sample; returns the faults that *just became active*
     * on this frame (empty on a quiet frame). Order is stable:
     * controller-fault first, then low-battery, then the temp channels.
     */
    fun onSample(data: VehicleData): List<DetectedFault> {
        val faults = mutableListOf<DetectedFault>()

        // ── Controller fault bitfield (rising edge) ──
        val faultNow = data.faultCode != 0L
        if (faultNow && !faultCodeArmed) {
            faults += DetectedFault(
                type = TYPE_CONTROLLER_FAULT,
                severity = SEVERITY_CRITICAL,
                message = "Controller fault 0x%X".format(data.faultCode)
            )
        }
        faultCodeArmed = faultNow

        // ── Low battery (wire flags bit6, rising edge) ──
        val lowBatteryNow = (data.flags and FLAG_LOW_BATTERY) != 0
        if (lowBatteryNow && !lowBatteryArmed) {
            val soc = if (data.batteryKnown) " (SoC ${data.batteryPercent}%)" else ""
            faults += DetectedFault(
                type = TYPE_BATT_LOW,
                severity = SEVERITY_WARNING,
                message = "Battery low$soc"
            )
        }
        lowBatteryArmed = lowBatteryNow

        // ── Temperatures (banded, with hysteresis) ──
        tempEdge("Motor", data.temperature, motorTempLevel)?.let { (level, fault) ->
            motorTempLevel = level
            fault?.let { faults += it.copy(type = TYPE_MOTOR_TEMP) }
        }
        if (data.batteryTempAvailable) {
            tempEdge("Battery pack", data.batteryTemperature, batteryTempLevel)?.let { (level, fault) ->
                batteryTempLevel = level
                fault?.let { faults += it.copy(type = TYPE_BATTERY_TEMP) }
            }
        }
        tempEdge("Controller", data.controllerTemperature, controllerTempLevel)?.let { (level, fault) ->
            controllerTempLevel = level
            fault?.let { faults += it.copy(type = TYPE_CONTROLLER_TEMP) }
        }

        return faults
    }

    /**
     * Re-arms every latch. Call on a fresh connection so a still-hot channel or
     * a lingering fault from the previous session is reported anew rather than
     * suppressed by stale edge state.
     */
    fun reset() {
        motorTempLevel = 0
        batteryTempLevel = 0
        controllerTempLevel = 0
        faultCodeArmed = false
        lowBatteryArmed = false
    }

    /**
     * Banded edge detection for one temperature channel.
     *
     * Returns `null` when nothing changed, else the new armed band plus an
     * optional fault to emit. Escalation (a hotter band than currently armed)
     * emits immediately; de-escalation only lowers the armed band once the
     * reading drops [TEMP_CLEAR_MARGIN_C] below the band boundary, so a value
     * sitting on 50 °C or 70 °C doesn't flap. The generic [DetectedFault]
     * carries a placeholder type the caller rewrites per channel.
     */
    private fun tempEdge(channel: String, tempC: Int, armed: Int): Pair<Int, DetectedFault?>? {
        val band = when {
            tempC >= TEMP_CRIT_C -> 2
            tempC >= TEMP_WARN_C -> 1
            else -> 0
        }
        if (band > armed) {
            val severity = if (band == 2) SEVERITY_CRITICAL else SEVERITY_WARNING
            val word = if (band == 2) "critical" else "high"
            return band to DetectedFault(
                type = "",
                severity = severity,
                message = "$channel temperature $word (${tempC}°C)"
            )
        }
        // De-escalate through the bands with a clear margin; never emit on cooldown.
        val lowered = when {
            armed >= 2 && tempC < TEMP_CRIT_C - TEMP_CLEAR_MARGIN_C ->
                if (tempC >= TEMP_WARN_C) 1 else 0
            armed >= 1 && tempC < TEMP_WARN_C - TEMP_CLEAR_MARGIN_C -> 0
            else -> armed
        }
        return if (lowered != armed) lowered to null else null
    }

    companion object {
        const val TEMP_WARN_C = 50
        const val TEMP_CRIT_C = 70

        /** Reading must drop this far below a band boundary before it re-arms. */
        const val TEMP_CLEAR_MARGIN_C = 5

        /** Wire `flags` bit6 — low battery (SoC ≤ 15%), per docs/capnpble.md. */
        const val FLAG_LOW_BATTERY = 0x40

        const val SEVERITY_INFO = 0
        const val SEVERITY_WARNING = 1
        const val SEVERITY_CRITICAL = 2

        const val TYPE_CONTROLLER_FAULT = "CONTROLLER_FAULT"
        const val TYPE_BATT_LOW = "BATT_LOW"
        const val TYPE_MOTOR_TEMP = "MOTOR_TEMP"
        const val TYPE_BATTERY_TEMP = "BATTERY_TEMP"
        const val TYPE_CONTROLLER_TEMP = "CONTROLLER_TEMP"
    }
}
