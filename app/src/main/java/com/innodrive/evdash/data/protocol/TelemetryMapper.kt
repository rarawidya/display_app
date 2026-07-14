package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.domain.model.VehicleData
import com.innodrive.evdash.domain.model.VehicleMode
import timber.log.Timber

/**
 * Maps a decoded [TelemetrySchema.VotolTelemetry] wire frame to the canonical
 * [VehicleData] domain model. Owns wire-format conversion only.
 *
 * Phase 1 audit invariant: this is the ONE place that translates a live wire
 * frame into VehicleData. rpm and speedKmh now arrive as real wire fields, so
 * they are read straight off the frame (not derived); power is still computed
 * via [TelemetryDerivations] so live and replay agree bit-for-bit.
 *
 * Wire-field handling notes (see capnp.md / telemetry.capnp):
 *  - `batteryDeciVolts` → volts (÷10).
 *  - `currentMotor` → amps (÷10). CONFIRMED signed deci-amps (negative = regen);
 *    power/energy/Wh-km/range are therefore live. The ~0.4 A idle zero-offset is
 *    left unsubtracted per the firmware reference decode.
 *  - `batteryPercent == 255` means "not yet known" (SoC CAN frame silent for
 *    ~1 s after MCU boot). We hold the previous value rather than show 0 %.
 *  - `tempBattery` (@8) is a real battery-pack temperature channel as of the
 *    2026-07-07 schema renumber, so `batteryTemperature`/`batteryTempAvailable`
 *    are now live (previously hardcoded 0 / false).
 *  - `odoMeters`/`tripMeters` (@12/@13) carry the board-integrated odometer in
 *    metres → surfaced as `odometerKm`/`tripKm` (÷1000). `batteryCurrent` (@2)
 *    is signed pack amps (positive = charging).
 */
class TelemetryMapper {

    fun map(payload: ByteArray, previous: VehicleData): VehicleData? {
        return try {
            val frame = TelemetrySchema.readFrom(payload)
            val voltageV = frame.batteryDeciVolts / 10f
            // `currentMotor` is signed deci-amps (÷10 = A, negative = regen) — CONFIRMED.
            val currentA = frame.currentMotor / 10f

            val batteryPercent =
                if (frame.batteryPercent == BATTERY_PERCENT_UNKNOWN) previous.batteryPercent
                else frame.batteryPercent.coerceIn(0, 100)

            VehicleData(
                speed = frame.speedKmh,
                batteryPercent = batteryPercent,
                voltage = voltageV,
                current = currentA,
                temperature = frame.motorTempC,
                // Real battery-pack temperature channel since the 2026-07-07 renumber.
                batteryTemperature = frame.batteryTempC,
                controllerTemperature = frame.controllerTempC,
                vehicleMode = mapMode(frame.driveMode),
                rpm = frame.rpm,
                power = TelemetryDerivations.powerFromVoltsAmps(voltageV, currentA),
                batteryCurrent = frame.batteryCurrent.toFloat(),
                odometerKm = frame.odoMeters / 1000f,
                tripKm = frame.tripMeters / 1000f,
                tripAKm = frame.tripAMeters / 1000f,
                tripBKm = frame.tripBMeters / 1000f,
                // Board firmware + OTA status + controller model (@17..@19/@23).
                // NANJING-only phaseA/phaseC/power (@20..@22) are decoded but not
                // surfaced: `power` on VehicleData stays the derived V×I so the
                // single-derivation invariant holds (the wire field is 0 on VOTOL).
                firmwareVersion = frame.fwVersion,
                otaState = frame.otaState,
                otaProgress = frame.otaProgress,
                controllerType = frame.controllerType,
                faultCode = frame.faultCode,
                flags = frame.flags,
                seq = frame.seq,
                // Availability per wire-protocol capabilities (capnpble.md §3).
                currentAvailable = TelemetryConstants.CURRENT_CHANNEL_CALIBRATED,
                batteryTempAvailable = true, // real tempBattery @8 channel
                batteryKnown = frame.batteryPercent != BATTERY_PERCENT_UNKNOWN,
                // Wall-clock at decode time. The wire has no timestamp field;
                // `seq` covers frame ordering / drop detection instead.
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode telemetry frame")
            null
        }
    }

    /** Wire drive mode: 1 = Eco, 2 = Urban, 3 = Sport (capnp.md §3). */
    private fun mapMode(value: Int): VehicleMode = when (value) {
        1 -> VehicleMode.ECO
        2 -> VehicleMode.NORMAL   // "Urban" on the cluster; NORMAL in the domain enum
        3 -> VehicleMode.SPORT
        else -> VehicleMode.PARK
    }

    private companion object {
        const val BATTERY_PERCENT_UNKNOWN = 255
    }
}
