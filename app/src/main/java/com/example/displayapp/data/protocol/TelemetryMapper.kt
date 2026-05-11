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
            VehicleData(
                speed = frame.speed / 10,
                batteryPercent = frame.battery.coerceIn(0, 100),
                voltage = frame.voltage / 100f,
                current = frame.current / 100f,
                temperature = frame.temperature,
                odometer = frame.odometer / 1000f, // meters → km
                vehicleMode = mapMode(frame.mode),
                leftIndicator = (frame.indicators and 0x01) != 0,
                rightIndicator = (frame.indicators and 0x02) != 0,
                headlamp = (frame.indicators and 0x04) != 0,
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
        else -> VehicleMode.PARK
    }
}
