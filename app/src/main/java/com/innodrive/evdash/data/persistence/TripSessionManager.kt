package com.innodrive.evdash.data.persistence

import com.innodrive.evdash.data.energy.EnergyAccumulator
import com.innodrive.evdash.data.fault.FaultDetector
import com.innodrive.evdash.data.persistence.dao.FaultEventDao
import com.innodrive.evdash.data.persistence.dao.TelemetryDao
import com.innodrive.evdash.data.persistence.dao.TripDao
import com.innodrive.evdash.data.persistence.entity.FaultEventEntity
import com.innodrive.evdash.data.persistence.entity.TripEntity
import com.innodrive.evdash.domain.model.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val telemetryLogger: TelemetryLogger,
    private val faultDetector: FaultDetector = FaultDetector(),
    // Fault inserts are fire-and-forget off the telemetry callback thread.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _activeTrip = MutableStateFlow<TripEntity?>(null)
    val activeTrip: StateFlow<TripEntity?> = _activeTrip.asStateFlow()

    val isRecording: Boolean get() = _activeTrip.value != null

    // Running aggregates
    private var maxSpeed = 0
    private var speedSum = 0L
    private var sampleCount = 0L
    // Distance is integrated from speed × dt (no odometer wire field).
    // Carries the previous sample so we can trapezoidal-integrate on the next.
    private var distanceMeters = 0.0
    private var lastSampleSpeedKmh = 0
    private var lastSampleTimestampMs = 0L
    // v5 analytics aggregates — power and peak temps.
    private var powerW100Sum = 0L
    private var maxPowerW100 = 0
    private var peakMotorTemp = Int.MIN_VALUE
    private var peakBatteryTemp = Int.MIN_VALUE
    private var peakControllerTemp = Int.MIN_VALUE

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
        distanceMeters = 0.0
        lastSampleSpeedKmh = initialData.speed
        lastSampleTimestampMs = initialData.timestamp
        powerW100Sum = 0
        maxPowerW100 = 0
        peakMotorTemp = Int.MIN_VALUE
        peakBatteryTemp = Int.MIN_VALUE
        peakControllerTemp = Int.MIN_VALUE
        energyAccumulator.reset()

        telemetryLogger.startRecording(tripId)
        Timber.i("Trip $tripId started")
        return tripId
    }

    suspend fun stopTrip(finalData: VehicleData) {
        val trip = _activeTrip.value ?: return

        telemetryLogger.stopRecording()

        // One last trapezoidal step so the final sample contributes.
        accumulateDistance(finalData.speed, finalData.timestamp)
        val distance = distanceMeters.toLong()

        // Discard trivial trips (a few metres of parking-lot roll) rather than
        // surface them as an "0.0 km" Last Ride. Deleting cascades the trip's
        // telemetry rows via the ForeignKey.
        if (distance < MIN_RECORDED_TRIP_METERS) {
            tripDao.deleteById(trip.id)
            _activeTrip.value = null
            Timber.i("Trip ${trip.id} discarded (${distance}m < ${MIN_RECORDED_TRIP_METERS}m)")
            return
        }

        val avgSpeed = if (sampleCount > 0) (speedSum / sampleCount).toInt() else 0

        val avgPowerW100 = if (sampleCount > 0) (powerW100Sum / sampleCount).toInt() else 0

        val completedTrip = trip.copy(
            endTime = System.currentTimeMillis(),
            distanceMeters = distance,
            maxSpeedKmh10 = maxSpeed,
            avgSpeedKmh10 = avgSpeed,
            endBattery = finalData.batteryPercent,
            sampleCount = sampleCount,
            energyUsedWh = energyAccumulator.usedWh(),
            energyRegenWh = energyAccumulator.regenWh(),
            avgPowerW100 = avgPowerW100,
            maxPowerW100 = maxPowerW100,
            peakMotorTempC = peakMotorTemp.coerceAtLeast(0),
            peakBatteryTempC = peakBatteryTemp.coerceAtLeast(0),
            peakControllerTempC = peakControllerTemp.coerceAtLeast(0)
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
        // Fault detection runs on every frame, recording or not — a fault that
        // occurs while idle (no active trip) is still worth logging; the event
        // just carries a null tripId.
        detectFaults(data)

        if (!isRecording) return

        telemetryLogger.log(data)

        // Update running aggregates
        val speedFixed = data.speed * 10
        if (speedFixed > maxSpeed) maxSpeed = speedFixed
        speedSum += speedFixed
        sampleCount++

        // Integrate trip distance from speed × dt (no odometer wire field).
        accumulateDistance(data.speed, data.timestamp)

        // Integrate V × I × dt for real energy accounting.
        energyAccumulator.onSample(data.voltage, data.current, data.timestamp)

        // v5 unified analytics: avg/max power + peak temps.
        val powerW100 = (data.power * 100f).toInt()
        powerW100Sum += powerW100
        if (powerW100 > maxPowerW100) maxPowerW100 = powerW100
        if (data.temperature > peakMotorTemp) peakMotorTemp = data.temperature
        if (data.batteryTemperature > peakBatteryTemp) peakBatteryTemp = data.batteryTemperature
        if (data.controllerTemperature > peakControllerTemp) peakControllerTemp = data.controllerTemperature
    }

    /**
     * Trapezoidal integration of speed × dt into [distanceMeters]. Caps
     * `dt` at [EnergyAccumulator.MAX_DT_MS] so a long pause between samples
     * (e.g. backgrounded service) doesn't fabricate huge distance.
     */
    private fun accumulateDistance(speedKmh: Int, timestampMs: Long) {
        val dtMsRaw = timestampMs - lastSampleTimestampMs
        if (lastSampleTimestampMs > 0L && dtMsRaw > 0L) {
            val dtMs = if (dtMsRaw > EnergyAccumulator.MAX_DT_MS) EnergyAccumulator.MAX_DT_MS else dtMsRaw
            val avgSpeedMs = ((lastSampleSpeedKmh + speedKmh) / 2.0) / 3.6
            distanceMeters += avgSpeedMs * dtMs / 1000.0
        }
        lastSampleSpeedKmh = speedKmh
        lastSampleTimestampMs = timestampMs
    }

    /**
     * Runs the pure [FaultDetector] over the sample and persists any faults it
     * raises on the rising edge. Off the caller's telemetry thread — inserts go
     * to Room on [scope]. Faults are stamped with the sample timestamp (wall
     * clock at decode) so they line up with the telemetry timeline, and linked
     * to the active trip when one is recording.
     */
    private fun detectFaults(data: VehicleData) {
        val faults = faultDetector.onSample(data)
        if (faults.isEmpty()) return
        val tripId = _activeTrip.value?.id
        val timestamp = data.timestamp
        scope.launch {
            faults.forEach { fault ->
                faultEventDao.insert(
                    FaultEventEntity(
                        timestamp = timestamp,
                        tripId = tripId,
                        type = fault.type,
                        severity = fault.severity,
                        message = fault.message
                    )
                )
                Timber.w("Fault logged: [${fault.type}] ${fault.message}")
            }
        }
    }

    /**
     * Logs a fault raised outside the telemetry stream (e.g. connection loss).
     * Stamped with the current wall clock since there is no sample to source a
     * timestamp from.
     */
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

    /**
     * Drops trips a previous process left in-progress (killed mid-ride). Their
     * in-memory aggregates are gone, so an incomplete row would show as a
     * perpetual "Recording…" in Logs and never surface as a Last Ride. Call once
     * at startup **before** any new recording can begin, or it would delete the
     * freshly started trip (telemetry rows cascade on delete).
     */
    suspend fun discardDanglingTrips() {
        val dropped = tripDao.deleteDangling()
        if (dropped > 0) Timber.i("Discarded $dropped dangling in-progress trip(s) at startup")
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

    companion object {
        /** Trips shorter than this (integrated metres) are discarded on stop. */
        const val MIN_RECORDED_TRIP_METERS = 50L
    }
}
