package com.example.displayapp.data.protocol

import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
import timber.log.Timber

/**
 * Maps a decoded Cap'n Proto TelemetryFrame to the domain VehicleData model.
 * Handles fixed-point → floating-point conversion and bitfield extraction.
 */
class TelemetryMapper {

    fun map(payload: ByteArray, previous: VehicleData): VehicleData? {
        return try {
            val frame = TelemetrySchema.readFrom(payload)
            val speedKmh = frame.speed / 10
            val voltageV = frame.voltage / 100f
            val currentA = frame.current / 100f
            VehicleData(
                speed = speedKmh,
                batteryPercent = frame.battery.coerceIn(0, 100),
                voltage = voltageV,
                current = currentA,
                temperature = frame.temperature,
                batteryTemperature = frame.batteryTemperature,
                controllerTemperature = frame.controllerTemperature,
                vehicleMode = mapMode(frame.mode),
                // Single source of truth for derived per-frame values.
                rpm = speedKmh * 100,
                power = voltageV * currentA,
                // Wall-clock at decode time, not `frame.timestamp`.
                //
                // `frame.timestamp` is a UInt32 millis count (wraps every ~49 days)
                // and is currently relative to MCU boot — see telemetry.capnp:14. Fine
                // for wire-side ordering, but Logs/replay/CSV need monotonic absolute
                // time. Switch to honoring it only once the MCU exposes either
                // (a) a wall-clock value or (b) a boot epoch we can add here.
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
