package com.example.displayapp.data.persistence.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A trip session. Created when recording starts, updated with summary stats on completion.
 *
 * Energy columns (v2+):
 *  - [energyUsedWh]: Wh integrated from positive-current samples (discharge).
 *  - [energyRegenWh]: Wh integrated from negative-current samples (regen).
 *  - Trips persisted under v1 default to 0/0; the UI surfaces these as "—" rather
 *    than the old `Δbatt × 0.6` heuristic so old data isn't misleadingly precise.
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
    val sampleCount: Long = 0,

    // ── v2: real energy accounting ────────────────────────────────────────────
    val energyUsedWh: Double = 0.0,
    val energyRegenWh: Double = 0.0,

    // ── v5: unified analytics aggregates ─────────────────────────────────────
    // Powers are stored centi-watts (×100); the int range covers ±214 kW with
    // two-decimal precision. Peaks are °C ints (matches per-sample columns).
    val avgPowerW100: Int = 0,
    val maxPowerW100: Int = 0,
    val peakMotorTempC: Int = 0,
    val peakBatteryTempC: Int = 0,
    val peakControllerTempC: Int = 0
)
