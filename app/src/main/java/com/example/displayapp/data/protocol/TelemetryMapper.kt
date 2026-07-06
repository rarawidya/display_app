package com.example.displayapp.data.protocol

import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
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
 *  - `motorCurrentRaw` is raw counts (currently 0, uncalibrated) — carried
 *    through as-is so power/energy stay wired, but they read ~0 until the
 *    firmware defines a scale and sign.
 *  - `batteryPercent == 255` means "not yet known" (SoC CAN frame silent for
 *    ~1 s after MCU boot). We hold the previous value rather than show 0 %.
 *  - Battery pack temperature is NOT on this wire (v1); [VehicleData] keeps the
 *    field for UI/persistence continuity, populated 0 (unknown).
 */
class TelemetryMapper {

    fun map(payload: ByteArray, previous: VehicleData): VehicleData? {
        return try {
            val frame = TelemetrySchema.readFrom(payload)
            val voltageV = frame.batteryDeciVolts / 10f
            val currentA = frame.motorCurrentRaw.toFloat()

            val batteryPercent =
                if (frame.batteryPercent == BATTERY_PERCENT_UNKNOWN) previous.batteryPercent
                else frame.batteryPercent.coerceIn(0, 100)

            VehicleData(
                speed = frame.speedKmh,
                batteryPercent = batteryPercent,
                voltage = voltageV,
                current = currentA,
                temperature = frame.motorTempC,
                // Not on the v1 wire — unknown until firmware adds a channel.
                batteryTemperature = 0,
                controllerTemperature = frame.controllerTempC,
                vehicleMode = mapMode(frame.driveMode),
                rpm = frame.rpm,
                power = TelemetryDerivations.powerFromVoltsAmps(voltageV, currentA),
                faultCode = frame.faultCode,
                flags = frame.flags,
                seq = frame.seq,
                // Availability per wire-protocol v1 capabilities (capnp.md).
                currentAvailable = TelemetryConstants.CURRENT_CHANNEL_CALIBRATED,
                batteryTempAvailable = false, // no battery-temp channel on the v1 wire
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
