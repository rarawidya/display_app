package com.example.displayapp.data.replay

import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.domain.replay.TripReplaySource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replay source that paces stored Room samples back at their original
 * wall-clock cadence (or scaled by [speedMultiplier]).
 *
 * Algorithm:
 *   1. Pull all samples for the trip (already indexed by tripId + timestamp).
 *   2. Emit them in order, delaying between emissions by
 *      `(currentSample.timestamp − previousSample.timestamp) / speedMultiplier`.
 *
 * Memory: loads the full sample list once. For a 1-hour trip at 20 Hz that's
 * 72k rows ≈ 3 MB — fine. Long trips could be paged later by switching to a
 * cursor-style approach, but this matches today's storage scale.
 */
class RoomTripReplaySource(
    private val telemetryDao: TelemetryDao
) : TripReplaySource {

    override fun play(tripId: Long, speedMultiplier: Float): Flow<TelemetryEntity> = flow {
        val multiplier = speedMultiplier.coerceAtLeast(0.01f)
        val samples = telemetryDao.getByTrip(tripId)
        if (samples.isEmpty()) return@flow

        var previousMs = samples.first().timestamp
        for (sample in samples) {
            val deltaMs = ((sample.timestamp - previousMs) / multiplier).toLong()
            if (deltaMs > 0) delay(deltaMs)
            emit(sample)
            previousMs = sample.timestamp
        }
    }
}
