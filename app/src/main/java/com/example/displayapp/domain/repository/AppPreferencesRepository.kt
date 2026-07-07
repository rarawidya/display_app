package com.example.displayapp.domain.repository

import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.domain.model.RetentionPeriod
import com.example.displayapp.domain.model.SpeedUnit
import com.example.displayapp.domain.model.TemperatureUnit
import com.example.displayapp.domain.model.TimeFormat
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for user app preferences.
 *
 * One snapshot Flow + setters per field. Implementations back this with
 * persistent storage (DataStore today, could be remote sync later).
 */
interface AppPreferencesRepository {
    val settings: Flow<AppSettings>

    suspend fun setSpeedUnit(value: SpeedUnit)
    suspend fun setTemperatureUnit(value: TemperatureUnit)
    suspend fun setTimeFormat(value: TimeFormat)
    suspend fun setRetention(value: RetentionPeriod)
    suspend fun setSimulatorMode(value: Boolean)
    suspend fun setSimulatorScenario(value: TelemetryScenario)
    suspend fun setAutoConnect(value: Boolean)
    suspend fun setShowDiagnosticsOverlay(value: Boolean)
    suspend fun setNotificationRelayEnabled(value: Boolean)
    suspend fun setTripBBaselineKm(value: Float)
    suspend fun setDevModeUnlocked(value: Boolean)
}
