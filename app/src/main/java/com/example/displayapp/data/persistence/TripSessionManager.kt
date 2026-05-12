package com.example.displayapp.data.persistence

import com.example.displayapp.data.energy.EnergyAccumulator
import com.example.displayapp.data.persistence.dao.FaultEventDao
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.entity.FaultEventEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.domain.model.VehicleData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Manages trip recording lifecycle.
 *
 * Trip lifecycle:
 *   startTrip() → [recording...] → stopTrip()
 *
 * During recording:
 * - TelemetryLogger buffers and batch-inserts samples
 * - TripSessionManager tracks aggregate stats (max speed, distance)
 * - Fault events are logged independently
 *
 * On stop:
 * - Final flush of telemetry buffer
 * - Trip entity updated with end time and summary stats
 */
class TripSessionManager(
    private val tripDao: TripDao,
    private val telemetryDao: TelemetryDao,
    private val faultEventDao: FaultEventDao,
    private val telemetryLogger: TelemetryLogger
) {
    private val _activeTrip = MutableStateFlow<TripEntity?>(null)
    val activeTrip: StateFlow<TripEntity?> = _activeTrip.asStateFlow()

    val isRecording: Boolean get() = _activeTrip.value != null

    // Running aggregates
    private var maxSpeed = 0
    private var speedSum = 0L
    private var sampleCount = 0L
    private var startOdometerMeters = 0L
    private var lastOdometerMeters = 0L

    /**
     * Integrates V × I × dt across the recording. Pure / testable — the trip
     * manager owns one instance per active trip and resets it on start.
     * Future analytics (eco-score, thermal exposure) can be wired into
     * onTelemetryUpdate alongside this accumulator without touching the
     * energy logic itself.
     */
    private val energyAccumulator = EnergyAccumulator()

    suspend fun startTrip(initialData: VehicleData): Long {
        // Close any lingering active trip
        _activeTrip.value?.let { stopTrip(initialData) }

        val trip = TripEntity(
            startTime = System.currentTimeMillis(),
            startBattery = initialData.batteryPercent
        )
        val tripId = tripDao.insert(trip)
        val savedTrip = trip.copy(id = tripId)
        _activeTrip.value = savedTrip

        // Reset aggregates
        maxSpeed = 0
        speedSum = 0
        sampleCount = 0
        startOdometerMeters = (initialData.odometer * 1000).toLong()
        lastOdometerMeters = startOdometerMeters
        energyAccumulator.reset()

        telemetryLogger.startRecording(tripId)
        Timber.i("Trip $tripId started")
        return tripId
    }

    suspend fun stopTrip(finalData: VehicleData) {
        val trip = _activeTrip.value ?: return

        telemetryLogger.stopRecording()

        lastOdometerMeters = (finalData.odometer * 1000).toLong()
        val distance = lastOdometerMeters - startOdometerMeters
        val avgSpeed = if (sampleCount > 0) (speedSum / sampleCount).toInt() else 0

        val completedTrip = trip.copy(
            endTime = System.currentTimeMillis(),
            distanceMeters = distance,
            maxSpeedKmh10 = maxSpeed,
            avgSpeedKmh10 = avgSpeed,
            endBattery = finalData.batteryPercent,
            sampleCount = sampleCount,
            energyUsedWh = energyAccumulator.usedWh(),
            energyRegenWh = energyAccumulator.regenWh()
        )
        tripDao.update(completedTrip)
        _activeTrip.value = null

        Timber.i(
            "Trip ${trip.id} ended: ${distance}m, ${sampleCount} samples, " +
                "used=%.1fWh regen=%.1fWh".format(
                    energyAccumulator.usedWh(),
                    energyAccumulator.regenWh()
                )
        )
    }

    /**
     * Called by the repository on every telemetry update during recording.
     */
    fun onTelemetryUpdate(data: VehicleData) {
        if (!isRecording) return

        telemetryLogger.log(data)

        // Update running aggregates
        val speedFixed = data.speed * 10
        if (speedFixed > maxSpeed) maxSpeed = speedFixed
        speedSum += speedFixed
        sampleCount++
        lastOdometerMeters = (data.odometer * 1000).toLong()

        // Integrate V × I × dt for real energy accounting.
        energyAccumulator.onSample(data.voltage, data.current, data.timestamp)
    }

    suspend fun logFault(type: String, severity: Int, message: String) {
        val event = FaultEventEntity(
            timestamp = System.currentTimeMillis(),
            tripId = _activeTrip.value?.id,
            type = type,
            severity = severity,
            message = message
        )
        faultEventDao.insert(event)
        Timber.w("Fault logged: [$type] $message")
    }

    fun observeTrips(): Flow<List<TripEntity>> = tripDao.observeAll()

    suspend fun getTrip(id: Long): TripEntity? = tripDao.getById(id)

    suspend fun deleteTrip(id: Long) {
        tripDao.deleteById(id)
        Timber.i("Trip $id deleted")
    }

    fun release() {
        telemetryLogger.release()
    }
}
