package com.innodrive.evdash.data.repository

import com.innodrive.evdash.data.preferences.AppPreferences
import com.innodrive.evdash.data.simulator.TelemetryScenario
import com.innodrive.evdash.domain.model.AppSettings
import com.innodrive.evdash.domain.model.RetentionPeriod
import com.innodrive.evdash.domain.model.SpeedUnit
import com.innodrive.evdash.domain.model.TemperatureUnit
import com.innodrive.evdash.domain.model.TimeFormat
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.Flow

class AppPreferencesRepositoryImpl(
    private val prefs: AppPreferences
) : AppPreferencesRepository {

    override val settings: Flow<AppSettings> = prefs.settings

    override suspend fun setSpeedUnit(value: SpeedUnit)             = prefs.setSpeedUnit(value)
    override suspend fun setTemperatureUnit(value: TemperatureUnit) = prefs.setTemperatureUnit(value)
    override suspend fun setTimeFormat(value: TimeFormat)           = prefs.setTimeFormat(value)
    override suspend fun setRetention(value: RetentionPeriod)       = prefs.setRetention(value)
    override suspend fun setSimulatorMode(value: Boolean)           = prefs.setSimulatorMode(value)
    override suspend fun setSimulatorScenario(value: TelemetryScenario) = prefs.setSimulatorScenario(value)
    override suspend fun setAutoConnect(value: Boolean)             = prefs.setAutoConnect(value)
    override suspend fun setShowDiagnosticsOverlay(value: Boolean)  = prefs.setShowDiagnosticsOverlay(value)
    override suspend fun setNotificationRelayEnabled(value: Boolean) = prefs.setNotificationRelayEnabled(value)
    override suspend fun setTripBBaselineKm(value: Float)           = prefs.setTripBBaselineKm(value)
    override suspend fun setDevModeUnlocked(value: Boolean)         = prefs.setDevModeUnlocked(value)
    override suspend fun setHotspotCredentials(ssid: String, password: String) =
        prefs.setHotspotCredentials(ssid, password)
}
