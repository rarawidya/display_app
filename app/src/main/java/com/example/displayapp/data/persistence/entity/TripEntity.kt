package com.example.displayapp.data.persistence.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A trip session. Created when recording starts, updated with summary stats on completion.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,             // epoch millis
    val endTime: Long? = null,       // null while recording
    val distanceMeters: Long = 0,
    val maxSpeedKmh10: Int = 0,      // max speed * 10
    val avgSpeedKmh10: Int = 0,      // avg speed * 10
    val startBattery: Int = 0,       // % at start
    val endBattery: Int? = null,     // % at end
    val sampleCount: Long = 0
)
