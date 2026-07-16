package com.innodrive.evdash.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.innodrive.evdash.data.simulator.TelemetryScenario
import com.innodrive.evdash.domain.model.AppSettings
import com.innodrive.evdash.domain.model.RetentionPeriod
import com.innodrive.evdash.domain.model.SpeedUnit
import com.innodrive.evdash.domain.model.TemperatureUnit
import com.innodrive.evdash.domain.model.TimeFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Dedicated DataStore file for app preferences. Theme + device prefs each keep
// their own DataStore — separation lets users "reset app preferences" without
// touching paired devices or theme choice.
private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * DataStore-backed access to user app preferences.
 *
 * Note: stale keys from earlier versions (sample_rate, auto_start_recording)
 * remain on-disk for users upgrading from older builds — they're simply
 * ignored by [settings]. Wiping them would require a migration, but they
 * cost a couple of bytes and never affect runtime.
 */
class AppPreferences(private val context: Context) {

    val settings: Flow<AppSettings> = context.appPrefsDataStore.data.map { p ->
        AppSettings(
            speedUnit = parseEnum(p[KEY_SPEED_UNIT], SpeedUnit.entries, SpeedUnit.KMH),
            temperatureUnit = parseEnum(p[KEY_TEMP_UNIT], TemperatureUnit.entries, TemperatureUnit.CELSIUS),
            timeFormat = parseEnum(p[KEY_TIME_FORMAT], TimeFormat.entries, TimeFormat.H24),
            retention = parseEnum(p[KEY_RETENTION], RetentionPeriod.entries, RetentionPeriod.DAYS_90),
            simulatorMode = p[KEY_SIMULATOR_MODE] ?: true,
            simulatorScenario = parseEnum(
                p[KEY_SIMULATOR_SCENARIO], TelemetryScenario.entries, TelemetryScenario.CITY_CRUISE
            ),
            autoConnect = p[KEY_AUTO_CONNECT] ?: true,
            showDiagnosticsOverlay = p[KEY_DIAGNOSTICS_OVERLAY] ?: false,
            notificationRelayEnabled = p[KEY_NOTIFICATION_RELAY] ?: false,
            devModeUnlocked = p[KEY_DEV_MODE_UNLOCKED] ?: false,
            hotspotSsid = p[KEY_HOTSPOT_SSID] ?: "",
            hotspotPassword = p[KEY_HOTSPOT_PSK] ?: ""
        )
    }

    // Note: function bodies (not `=`) keep the return type as Unit. Using
    // `= edit { … }` would infer `Preferences` and break the repository contract.
    suspend fun setSpeedUnit(value: SpeedUnit) {
        context.appPrefsDataStore.edit { it[KEY_SPEED_UNIT] = value.name }
    }
    suspend fun setTemperatureUnit(value: TemperatureUnit) {
        context.appPrefsDataStore.edit { it[KEY_TEMP_UNIT] = value.name }
    }
    suspend fun setTimeFormat(value: TimeFormat) {
        context.appPrefsDataStore.edit { it[KEY_TIME_FORMAT] = value.name }
    }
    suspend fun setRetention(value: RetentionPeriod) {
        context.appPrefsDataStore.edit { it[KEY_RETENTION] = value.name }
    }
    suspend fun setSimulatorMode(value: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_SIMULATOR_MODE] = value }
    }
    suspend fun setSimulatorScenario(value: TelemetryScenario) {
        context.appPrefsDataStore.edit { it[KEY_SIMULATOR_SCENARIO] = value.name }
    }
    suspend fun setAutoConnect(value: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_AUTO_CONNECT] = value }
    }
    suspend fun setShowDiagnosticsOverlay(value: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_DIAGNOSTICS_OVERLAY] = value }
    }
    suspend fun setNotificationRelayEnabled(value: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_NOTIFICATION_RELAY] = value }
    }
    suspend fun setDevModeUnlocked(value: Boolean) {
        context.appPrefsDataStore.edit { it[KEY_DEV_MODE_UNLOCKED] = value }
    }
    suspend fun setHotspotCredentials(ssid: String, password: String) {
        context.appPrefsDataStore.edit {
            it[KEY_HOTSPOT_SSID] = ssid
            it[KEY_HOTSPOT_PSK] = password
        }
    }

    private companion object {
        val KEY_SPEED_UNIT          = stringPreferencesKey("speed_unit")
        val KEY_TEMP_UNIT           = stringPreferencesKey("temp_unit")
        val KEY_TIME_FORMAT         = stringPreferencesKey("time_format")
        val KEY_RETENTION           = stringPreferencesKey("retention")
        val KEY_SIMULATOR_MODE      = booleanPreferencesKey("simulator_mode")
        val KEY_SIMULATOR_SCENARIO  = stringPreferencesKey("simulator_scenario")
        val KEY_AUTO_CONNECT        = booleanPreferencesKey("auto_connect")
        val KEY_DIAGNOSTICS_OVERLAY = booleanPreferencesKey("diagnostics_overlay")
        val KEY_NOTIFICATION_RELAY  = booleanPreferencesKey("notification_relay_enabled")
        val KEY_DEV_MODE_UNLOCKED   = booleanPreferencesKey("dev_mode_unlocked")
        // Hotspot credentials for the board's Wi-Fi STA join. Plaintext in the app's
        // private DataStore — same trust level as Android's own saved Wi-Fi networks.
        val KEY_HOTSPOT_SSID        = stringPreferencesKey("hotspot_ssid")
        val KEY_HOTSPOT_PSK         = stringPreferencesKey("hotspot_psk")

        // Defensive parse — bad/older string keys silently fall back to default.
        private fun <E : Enum<E>> parseEnum(raw: String?, values: List<E>, default: E): E {
            return values.firstOrNull { it.name == raw } ?: default
        }
    }
}
