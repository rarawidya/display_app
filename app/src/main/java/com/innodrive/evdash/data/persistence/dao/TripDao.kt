package com.innodrive.evdash.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.innodrive.evdash.data.persistence.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    suspend fun getAll(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE endTime IS NULL LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Drop trips left in-progress (endTime NULL) by a killed process. */
    @Query("DELETE FROM trips WHERE endTime IS NULL")
    suspend fun deleteDangling(): Int

    @Query("DELETE FROM trips WHERE startTime < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
