package com.innodrive.evdash.domain.replay

import com.innodrive.evdash.data.persistence.entity.TelemetryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Emits a recorded trip's telemetry as a Flow at a controlled rate.
 *
 * Intended to power a future "replay" surface on the Drive screen that lets
 * the user scrub through a finished trip with the cockpit gauges responding
 * as if telemetry were live.
 *
 * The interface matches the live-telemetry contract closely enough that the
 * Drive ViewModel can swap its source between [VehicleRepository] and this
 * one without UI changes — only the emission cadence differs.
 */
interface TripReplaySource {

    /**
     * One-shot stream of [TelemetryEntity] rows for [tripId], paced to the
     * original wall-clock interval scaled by [speedMultiplier].
     *
     * @param speedMultiplier 1.0 = real-time, 2.0 = 2×, 0.5 = half-speed.
     */
    fun play(tripId: Long, speedMultiplier: Float = 1.0f): Flow<TelemetryEntity>
}
