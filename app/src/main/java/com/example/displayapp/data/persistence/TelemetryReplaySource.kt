package com.example.displayapp.data.persistence

import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * Replays recorded telemetry as a Flow<VehicleData> at the original recording rate.
 *
 * Architecture:
 * - Loads telemetry for a trip from the database
 * - Emits samples with the original inter-sample timing
 * - Supports playback speed multiplier (1x, 2x, 4x, 0.5x)
 * - Can be used with the existing dashboard UI by swapping
 *   the live telemetry flow for the replay flow
 *
 * For large trips (>10k samples), use chunked loading to avoid OOM.
 */
class TelemetryReplaySource(
    private val telemetryDao: TelemetryDao
) {

    /**
     * Replays a trip's telemetry as a cold Flow.
     * Emits VehicleData at the original sample rate (adjusted by [speedMultiplier]).
     *
     * @param tripId Trip to replay
     * @param speedMultiplier Playback speed (1.0 = realtime, 2.0 = 2x speed)
     */
    fun replay(tripId: Long, speedMultiplier: Float = 1f): Flow<VehicleData> = flow {
        val samples = telemetryDao.getByTrip(tripId)
        if (samples.isEmpty()) {
            Timber.w("No telemetry data for trip $tripId")
            return@flow
        }

        Timber.i("Replaying trip $tripId: ${samples.size} samples at ${speedMultiplier}x")

        var previousTimestamp = samples.first().timestamp

        for (sample in samples) {
            val elapsed = sample.timestamp - previousTimestamp
            if (elapsed > 0) {
                val adjustedDelay = (elapsed / speedMultiplier).toLong()
                delay(adjustedDelay.coerceAtMost(MAX_DELAY_MS))
            }
            previousTimestamp = sample.timestamp
            emit(entityToVehicleData(sample))
        }

        Timber.i("Replay complete for trip $tripId")
    }

    /**
     * Returns a downsampled overview of a trip for graph rendering.
     * Target: ~500 points regardless of trip duration.
     */
    suspend fun getGraphData(tripId: Long, maxPoints: Int = 500): List<VehicleData> {
        val totalSamples = telemetryDao.countByTrip(tripId)
        if (totalSamples == 0L) return emptyList()

        val sampleEvery = (totalSamples / maxPoints).toInt().coerceAtLeast(1)
        val samples = telemetryDao.getDownsampled(tripId, sampleEvery)
        return samples.map { entityToVehicleData(it) }
    }

    /**
     * Returns telemetry within a time window (for zoomed graph views).
     */
    suspend fun getTimeRange(tripId: Long, startMs: Long, endMs: Long): List<VehicleData> {
        return telemetryDao.getRange(tripId, startMs, endMs)
            .map { entityToVehicleData(it) }
    }

    private fun entityToVehicleData(entity: TelemetryEntity): VehicleData {
        // Mirror TelemetryMapper exactly so replay produces the same canonical
        // VehicleData shape (including derived rpm/power) the live decode path
        // would emit for these wire values.
        val speedKmh = entity.speed / 10
        val voltageV = entity.voltage / 100f
        val currentA = entity.current / 100f
        return VehicleData(
            speed = speedKmh,
            batteryPercent = entity.battery,
            voltage = voltageV,
            current = currentA,
            temperature = entity.temperature,
            batteryTemperature = entity.batteryTemperature,
            controllerTemperature = entity.controllerTemperature,
            vehicleMode = VehicleMode.entries.getOrElse(entity.mode) { VehicleMode.PARK },
            timestamp = entity.timestamp,
            rpm = speedKmh * 100,
            power = voltageV * currentA
        )
    }

    companion object {
        private const val MAX_DELAY_MS = 2000L
    }
}
