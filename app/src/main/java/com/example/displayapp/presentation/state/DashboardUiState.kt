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
    val speed: Int = 0,
    val maxSpeed: Int = 120,
    val batteryPercent: Int = 0,
    val voltage: String = "0.0",
    val current: String = "0.0",
    val temperature: Int = 0,                    // engine / motor temperature (deg C)
    val controllerTemperature: Int = 0,          // controller temperature (deg C)
    val batteryTemperature: Int = 0,             // battery-pack temperature (deg C)
    val odometer: String = "0.0",
    val vehicleMode: VehicleMode = VehicleMode.PARK,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val headlamp: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val tripStats: TripStatsState = TripStatsState()
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
