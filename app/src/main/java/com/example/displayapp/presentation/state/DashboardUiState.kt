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
    val odometer: Float = 0f,                    // km
    val vehicleMode: VehicleMode = VehicleMode.PARK,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val headlamp: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
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

@Immutable
data class DiagnosticsState(
    val framesPerSecond: Int = 0,
    val framesDecoded: Long = 0,
    val crcErrors: Long = 0,
    val syncLosses: Long = 0,
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
