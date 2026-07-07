package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.VehicleMode

/**
 * Immutable UI state for the dashboard screen.
 *
 * This is the single source of truth for what the UI renders.
 * The ViewModel maps domain models into this state, performing
 * all formatting and derivation before emission. The UI layer
 * does zero computation — it only renders.
 *
 * @Immutable tells Compose that if the reference hasn't changed,
 * the content hasn't changed — enabling aggressive recomposition skipping.
 */
@Immutable
data class DashboardUiState(
    val speed: Int = 0,                          // km/h
    val rpm: Int = 0,                            // derived in TelemetryMapper (speed×100)
    val maxSpeed: Int = 120,
    val batteryPercent: Int = 0,
    val voltage: Float = 0f,                     // V
    val current: Float = 0f,                     // A (signed; negative = regen)
    val power: Float = 0f,                       // W (V×I), derived in TelemetryMapper
    val temperature: Int = 0,                    // motor temp (°C)
    val controllerTemperature: Int = 0,          // wire field (°C)
    val batteryTemperature: Int = 0,             // wire field (°C)
    /** Pack is charging — derived from `batteryCurrent` (@2) with hysteresis. */
    val charging: Boolean = false,
    val odometer: Float = 0f,                    // km, lifetime (odoMeters @12)
    val tripOdometer: Float = 0f,                // km, Trip A — vehicle trip (tripMeters @13)
    val tripBOdometer: Float = 0f,               // km, Trip B — app-tracked (odometer − baseline)
    val vehicleMode: VehicleMode = VehicleMode.PARK,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val headlamp: Boolean = false,
    // ── Warning-lamp telltales, decoded from VotolTelemetry flags/faultCode ──
    val engineRunning: Boolean = false,  // flags bit0
    val brakeActive: Boolean = false,    // flags bit1
    val reverseActive: Boolean = false,  // flags bit3
    val faultActive: Boolean = false,    // faultCode != 0
    val faultCode: Long = 0,
    // Field availability — drives "—" vs a numeric readout on the Drive tiles.
    val currentAvailable: Boolean = true,     // current & power
    val batteryTempAvailable: Boolean = true,
    val batteryKnown: Boolean = true,         // SoC (false while wire sends 255)
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val rssi: Int? = null,                       // connected-link signal strength (dBm)
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val tripStats: TripStatsState = TripStatsState(),
    val efficiency: EfficiencyState = EfficiencyState()
)

/**
 * Live efficiency snapshot consumed by the Drive tiles. Both Wh/km and range
 * are nullable so the UI can render "—" when there isn't yet enough data
 * (idle, just connected, or covered less than 10 m in the rolling window).
 */
@Immutable
data class EfficiencyState(
    val whPerKm: Float? = null,
    val rangeKm: Float? = null
)

/**
 * Lightweight in-memory per-drive summary for the Drive tab footer.
 * Heavier persisted stats live in TripEntity (Logs tab).
 */
@Immutable
data class TripStatsState(
    val avgSpeed: Int = 0,           // km/h
    val maxSpeed: Int = 0,           // km/h
    val distanceKm: Float = 0f,
    val durationSec: Long = 0
)

/**
 * Mirrors `DiagnosticsRepository.DiagnosticsSnapshot` 1:1 so the overlay reads
 * the same counters Settings → Diagnostics does. The Dashboard ViewModel
 * passes the snapshot through as-is — no UI-local accounting.
 */
@Immutable
data class DiagnosticsState(
    val framesPerSecond: Int = 0,
    val framesDecoded: Long = 0,
    val crcErrors: Long = 0,
    val syncLosses: Long = 0,
    val reconnects: Long = 0,
    val notificationsPushed: Long = 0,
    val notificationsDropped: Long = 0,
    val lastUpdateMs: Long = 0
)

/**
 * Derived alert levels for UI color coding.
 * Computed in the ViewModel, not in the composable.
 */
enum class AlertLevel { NORMAL, WARNING, CRITICAL }

fun batteryAlertLevel(percent: Int): AlertLevel = when {
    percent > 50 -> AlertLevel.NORMAL
    percent > 20 -> AlertLevel.WARNING
    else -> AlertLevel.CRITICAL
}

fun temperatureAlertLevel(tempCelsius: Int): AlertLevel = when {
    tempCelsius < 50 -> AlertLevel.NORMAL
    tempCelsius < 70 -> AlertLevel.WARNING
    else -> AlertLevel.CRITICAL
}
