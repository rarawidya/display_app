package com.example.displayapp.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    /**
     * Batch insert for high-frequency telemetry.
     * Called from the ring buffer flush (every 1 second = ~20 rows per batch).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(samples: List<TelemetryEntity>)

    /**
     * Get telemetry for a trip within a time range (for replay/graphing).
     */
    @Query("""
        SELECT * FROM telemetry
        WHERE tripId = :tripId AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp ASC
    """)
    suspend fun getRange(tripId: Long, startMs: Long, endMs: Long): List<TelemetryEntity>

    /**
     * Get all telemetry for a trip (for CSV export).
     */
    @Query("SELECT * FROM telemetry WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getByTrip(tripId: Long): List<TelemetryEntity>

    /**
     * Stream telemetry as a Flow for live replay playback.
     */
    @Query("SELECT * FROM telemetry WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun observeByTrip(tripId: Long): Flow<List<TelemetryEntity>>

    /**
     * Get sample count for a trip.
     */
    @Query("SELECT COUNT(*) FROM telemetry WHERE tripId = :tripId")
    suspend fun countByTrip(tripId: Long): Long

    /**
     * Delete telemetry older than a given timestamp (retention policy).
     */
    @Query("DELETE FROM telemetry WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /**
     * Get the oldest telemetry timestamp in the database.
     */
    @Query("SELECT MIN(timestamp) FROM telemetry")
    suspend fun getOldestTimestamp(): Long?

    /**
     * Downsampled query: picks every Nth row for graph overview.
     *
     * The stride is taken **relative to the trip's first row** (`id - MIN(id)`), not the
     * global autoincrement id. A trip's rows form a contiguous id block, but that block's
     * offset from a multiple of `sampleEvery` is arbitrary — a bare `id % sampleEvery = 0`
     * could match zero rows for a short trip (empty sparkline). Anchoring to `MIN(id)`
     * guarantees the first row is always included and rows are evenly spaced by exactly
     * `sampleEvery`, so the result count is predictable (~total/sampleEvery).
     */
    @Query("""
        SELECT * FROM telemetry
        WHERE tripId = :tripId
          AND ((id - (SELECT MIN(id) FROM telemetry WHERE tripId = :tripId)) % :sampleEvery) = 0
        ORDER BY timestamp ASC
    """)
    suspend fun getDownsampled(tripId: Long, sampleEvery: Int): List<TelemetryEntity>
}
