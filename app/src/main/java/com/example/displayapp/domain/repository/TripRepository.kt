package com.example.displayapp.domain.repository

import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import kotlinx.coroutines.flow.Flow

/**
 * Aggregates trip + telemetry access behind a single domain seam.
 *
 * The presentation layer never touches DAOs directly — it goes through this
 * repository so the data shape stays consistent and the data source can be
 * swapped (e.g. remote sync, file-import) without changing the ViewModels.
 */
interface TripRepository {

    /** Reactive stream of all trips (newest first). */
    fun observeAllTrips(): Flow<List<TripEntity>>

    suspend fun getTrip(tripId: Long): TripEntity?

    /**
     * Full sample timeline for a trip. May contain tens of thousands of points
     * for long trips — callers should downsample for UI rendering.
     */
    suspend fun getTripTelemetry(tripId: Long): List<TelemetryEntity>

    /**
     * Downsampled telemetry: every Nth row by primary key, ordered by time.
     * Cheap for indexed tables and produces visually smooth lines.
     *
     * @param sampleEvery 1 = all rows, 20 ≈ 1 Hz from 20 Hz raw.
     */
    suspend fun getDownsampledTelemetry(tripId: Long, sampleEvery: Int): List<TelemetryEntity>

    suspend fun deleteTrip(tripId: Long)
}
