package com.innodrive.evdash.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.innodrive.evdash.data.persistence.entity.FaultEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaultEventDao {

    @Insert
    suspend fun insert(event: FaultEventEntity)

    @Query("SELECT * FROM fault_events WHERE tripId = :tripId ORDER BY timestamp DESC")
    suspend fun getByTrip(tripId: Long): List<FaultEventEntity>

    @Query("SELECT * FROM fault_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<FaultEventEntity>

    @Query("SELECT * FROM fault_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<FaultEventEntity>>

    @Query("DELETE FROM fault_events WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
