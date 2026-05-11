package com.example.displayapp.data.persistence.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single telemetry sample stored at up to 20Hz during a trip.
 *
 * Storage optimization:
 * - Integers/shorts instead of floats (same as wire format)
 * - No string fields
 * - Indexed by tripId + timestamp for efficient range queries and replay
 * - Foreign key to TripEntity ensures orphan cleanup on trip delete
 *
 * At 20Hz with ~40 bytes/row, a 1-hour trip produces:
 *   20 * 3600 = 72,000 rows ≈ 2.9 MB (well within mobile storage)
 */
@Entity(
    tableName = "telemetry",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["tripId", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,       // epoch millis
    val speed: Int,            // km/h * 10
    val battery: Int,          // 0-100
    val voltage: Int,          // V * 100
    val current: Int,          // A * 100 (signed)
    val temperature: Int,      // celsius
    val odometer: Long,        // meters
    val mode: Int,             // enum ordinal
    val indicators: Int        // bitfield
)
