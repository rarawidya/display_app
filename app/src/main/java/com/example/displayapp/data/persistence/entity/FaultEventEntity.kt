package com.example.displayapp.data.persistence.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fault/event log entry. Records notable events during operation:
 * - Connection loss
 * - CRC errors exceeding threshold
 * - Temperature warnings
 * - Battery critical
 * - Mode changes
 */
@Entity(
    tableName = "fault_events",
    indices = [Index(value = ["timestamp"])]
)
data class FaultEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tripId: Long? = null,
    val type: String,             // "CONN_LOST", "CRC_ERROR", "TEMP_WARN", "BATT_CRITICAL"
    val severity: Int,            // 0=info, 1=warning, 2=critical
    val message: String
)
