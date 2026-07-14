package com.innodrive.evdash.domain.repository

import com.innodrive.evdash.data.simulator.TelemetryScenario
import com.innodrive.evdash.domain.model.AppSettings
import com.innodrive.evdash.domain.model.RetentionPeriod
import com.innodrive.evdash.domain.model.SpeedUnit
import com.innodrive.evdash.domain.model.TemperatureUnit
import com.innodrive.evdash.domain.model.TimeFormat
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
    suspend fun setHotspotCredentials(ssid: String, password: String)
}
