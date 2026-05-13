package com.example.displayapp.data.protocol

import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
import timber.log.Timber

/**
 * Maps a decoded Cap'n Proto [TelemetryFrame] to the canonical [VehicleData]
 * domain model. Owns wire-format conversion only — all *derivations* (rpm,
 * power) route through [TelemetryDerivations] so every surface in the app
 * (Drive, Charts, Trip Detail, replay, CSV) reads bit-identical values.
 *
 * Phase 1 audit invariant: this is the ONE place that translates a live
 * wire frame into VehicleData. Nothing downstream may re-derive rpm or
 * power locally; they read the fields off VehicleData / TelemetryEntity
 * and trust [TelemetryDerivations.decodeEntity] for persisted samples.
 */
class TelemetryMapper {

    fun map(payload: ByteArray, previous: VehicleData): VehicleData? {
        return try {
            val frame = TelemetrySchema.readFrom(payload)
            val speedKmh = TelemetryDerivations.speedKmhFromWire(frame.speed)
            val voltageV = TelemetryDerivations.voltageFromWire(frame.voltage)
            val currentA = TelemetryDerivations.currentFromWire(frame.current)
            VehicleData(
                speed = speedKmh,
                batteryPercent = frame.battery.coerceIn(0, 100),
                voltage = voltageV,
                current = currentA,
                temperature = frame.temperature,
                batteryTemperature = frame.batteryTemperature,
                controllerTemperature = frame.controllerTemperature,
                vehicleMode = mapMode(frame.mode),
                rpm = TelemetryDerivations.rpmFromSpeedKmh(speedKmh),
                power = TelemetryDerivations.powerFromVoltsAmps(voltageV, currentA),
                // Wall-clock at decode time, not `frame.timestamp`.
                //
                // `frame.timestamp` is a UInt32 millis count (wraps every ~49 days)
                // and is currently relative to MCU boot — see telemetry.capnp:14. Fine
                // for wire-side ordering, but Logs/replay/CSV need monotonic absolute
                // time. Phase 3 will introduce a per-trip `bootEpochMs` so we can honor
                // the MCU clock while keeping replay deterministic; for now wall-clock
                // is the only reliable absolute reference.
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode telemetry frame")
            null
        }
    }

    private fun mapMode(value: Int): VehicleMode = when (value) {
        0 -> VehicleMode.PARK
        1 -> VehicleMode.ECO
        2 -> VehicleMode.NORMAL
        3 -> VehicleMode.SPORT
        4 -> VehicleMode.REGEN
        else -> VehicleMode.PARK
    }
}
