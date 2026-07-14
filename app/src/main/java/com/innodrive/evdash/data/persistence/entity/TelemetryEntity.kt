package com.innodrive.evdash.data.persistence.entity

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
    val temperature: Int,      // motor temp °C (matches VehicleData.temperature)
    val mode: Int,             // enum ordinal
    // ── v3: real temp channels (schema split) ────────────────────────────────
    val batteryTemperature: Int = 0,
    val controllerTemperature: Int = 0,
    // odometer and indicators were dropped in schema v4 — see MIGRATION_3_4.
    // Distance is integrated from speed × dt in the analytics layer.

    // ── v6: persisted derivations ────────────────────────────────────────────
    // rpm and power are derived in TelemetryDerivations at decode time. v6
    // persists them so a future change to either formula doesn't retroactively
    // alter recorded trips — replay/CSV/Trip Detail read these columns
    // verbatim. Both are nullable: pre-v6 rows carry NULL and the read-side
    // (TelemetryDerivations.decodeEntity) falls back to recomputing from
    // the wire fields for backward compatibility.
    val rpm: Int? = null,
    val powerW: Float? = null,

    // ── v7: persisted wire status fields ─────────────────────────────────────
    // `faultCode` (controller fault bitfield) and `flags` (run/brake/moving/
    // reverse/park/lowBattery/regen bits) are independent wire channels — they
    // can't be recomputed from other columns, so unlike rpm/power they're stored
    // NOT NULL with a 0 default. Pre-v7 rows read 0 (no fault, no flags set),
    // matching what decodeEntity produced before they were persisted. Persisting
    // them lets replay / Trip Detail show the same telltales + faults the live
    // Drive page showed for the recorded frames.
    val faultCode: Long = 0,
    val flags: Int = 0
)
