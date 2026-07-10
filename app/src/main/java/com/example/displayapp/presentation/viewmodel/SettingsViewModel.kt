package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
import com.example.displayapp.data.notification.BoardWifiCommands
import com.example.displayapp.data.notification.PhoneNotificationSender
import com.example.displayapp.data.permissions.PermissionStatusProvider
import com.example.displayapp.data.persistence.StorageInfo
import com.example.displayapp.data.persistence.StorageInfoProvider
import com.example.displayapp.data.preferences.DevicePreferences
import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.model.RetentionPeriod
import com.example.displayapp.domain.model.SpeedUnit
import com.example.displayapp.domain.model.TemperatureUnit
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.domain.model.TimeFormat
import com.example.displayapp.domain.repository.AppPreferencesRepository
import com.example.displayapp.domain.repository.ThemeRepository
import com.example.displayapp.domain.repository.VehicleRepository
import com.example.displayapp.presentation.state.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single ViewModel for the Settings screen.
 *
 * Architecture rationale:
 * - Aggregating multiple Flows here keeps the Composable layer "stateless" —
 *   it observes one [StateFlow] and recomposes only when the snapshot changes.
 * - Theme is owned by [ThemeRepository] (kept separate because [MainActivity]
 *   reads it via [ThemeViewModel] to provide the live MaterialTheme). The
 *   Settings VM just edits through [ThemeRepository] directly.
 * - App preferences are owned by [AppPreferencesRepository] (DataStore).
 * - Connection + saved device are read from [VehicleRepository] +
 *   [DevicePreferences].
 * - Diagnostics are read from [DiagnosticsRepository] (process-wide hub).
 * - Storage info is recomputed manually via [refreshStorage] (file-system
 *   I/O can't live in a hot Flow).
 * - Permissions are recomputed when [refreshPermissions] is called by the
 *   screen on RESUMED — required because Android doesn't expose a Flow of
 *   permission grants and the user may grant from system Settings.
 */
class SettingsViewModel(
    private val themeRepository: ThemeRepository,
    private val appPreferences: AppPreferencesRepository,
    private val devicePreferences: DevicePreferences,
    private val vehicleRepository: VehicleRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val storageProvider: StorageInfoProvider,
    private val permissionProvider: PermissionStatusProvider,
    private val notificationSender: PhoneNotificationSender,
    private val onSimulatorModeChange: suspend (Boolean) -> Unit,
    private val onSimulatorScenarioChange: suspend (TelemetryScenario) -> Unit,
    private val onRunNavDemo: () -> Unit,
    private val onStopNavDemo: () -> Unit,
    appVersion: String,
    appBuildNumber: String
) : ViewModel() {

    /** One-shot user-facing result of a board Wi-Fi action (snackbar); null = none. */
    private val _wifiSendResult = MutableStateFlow<String?>(null)
    val wifiSendResult: StateFlow<String?> = _wifiSendResult
    fun consumeWifiSendResult() { _wifiSendResult.value = null }

    /** Developer: play the scripted navigation route → BLE nav frames (0xAF06). */
    fun runNavDemo() = onRunNavDemo()

    /** Developer: stop the navigation demo. */
    fun stopNavDemo() = onStopNavDemo()

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    private val _permissions = MutableStateFlow(permissionProvider.snapshot())

    // Bundle five upstream flows together so the final combine fits in 4 args
    // (kotlinx-coroutines' combine has typed overloads up to 5-ary; nesting
    // keeps the type inference happy without resorting to Array<Any?> casts).
    private val coreFlow = combine(
        themeRepository.settings,
        appPreferences.settings,
        devicePreferences.lastDevice,
        vehicleRepository.connectionState,
        diagnosticsRepository.snapshot
    ) { theme, app, device, conn, diag ->
        CoreBundle(theme, app, device, conn, diag)
    }

    val state: StateFlow<SettingsUiState> = combine(
        coreFlow,
        diagnosticsRepository.logs,
        _permissions,
        _storage
    ) { core, logs, perms, storage ->
        SettingsUiState(
            theme           = core.theme,
            app             = core.app,
            savedDevice     = core.device,
            connectionState = core.conn,
            diagnostics     = core.diag,
            diagnosticsLogs = logs,
            permissions     = perms,
            storage         = storage,
            appVersion      = appVersion,
            appBuildNumber  = appBuildNumber
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            appVersion = appVersion,
            appBuildNumber = appBuildNumber,
            permissions = permissionProvider.snapshot()
        )
    )

    init {
        refreshStorage()
    }

    /* ---------------------- Theme ---------------------- */

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeRepository.setMode(mode) }
    }

    /* ---------------------- App prefs ---------------------- */

    fun setSpeedUnit(value: SpeedUnit) = viewModelScope.launch { appPreferences.setSpeedUnit(value) }
    fun setTemperatureUnit(value: TemperatureUnit) = viewModelScope.launch { appPreferences.setTemperatureUnit(value) }
    fun setTimeFormat(value: TimeFormat) = viewModelScope.launch { appPreferences.setTimeFormat(value) }
    fun setRetention(value: RetentionPeriod) = viewModelScope.launch { appPreferences.setRetention(value) }
    fun setAutoConnect(value: Boolean) = viewModelScope.launch {
        appPreferences.setAutoConnect(value)
        devicePreferences.setAutoConnect(value)
    }
    fun setShowDiagnosticsOverlay(value: Boolean) = viewModelScope.launch { appPreferences.setShowDiagnosticsOverlay(value) }
    fun setNotificationRelayEnabled(value: Boolean) = viewModelScope.launch { appPreferences.setNotificationRelayEnabled(value) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { themeRepository.setDynamicColor(enabled) }

    /* ---------------------- Developer mode ---------------------- */

    fun unlockDeveloperMode() {
        viewModelScope.launch { appPreferences.setDevModeUnlocked(true) }
    }

    /** Called from DeveloperScreen — lets the user re-lock the back door. */
    fun lockDeveloperMode() {
        viewModelScope.launch { appPreferences.setDevModeUnlocked(false) }
    }

    /* ---------------------- Simulator ---------------------- */

    fun setSimulatorMode(value: Boolean) {
        viewModelScope.launch {
            appPreferences.setSimulatorMode(value)
            onSimulatorModeChange(value)
        }
    }

    fun setSimulatorScenario(scenario: TelemetryScenario) {
        viewModelScope.launch {
            appPreferences.setSimulatorScenario(scenario)
            onSimulatorScenarioChange(scenario)
        }
    }

    /* ---------------------- Device ---------------------- */

    fun forgetDevice() {
        viewModelScope.launch { devicePreferences.clear() }
    }

    /* ---------------------- Vehicle internet (Wi-Fi STA) ---------------------- */

    /** Persist the phone-hotspot credentials the board will join with. */
    fun setHotspotCredentials(ssid: String, password: String) {
        viewModelScope.launch { appPreferences.setHotspotCredentials(ssid.trim(), password) }
    }

    /**
     * Sending the credentials to the board now happens automatically from the header
     * hotspot button (see [com.example.displayapp.data.notification.BoardWifiConnector]),
     * so there's no separate "send" action here — this screen only sets/edits them.
     */

    /** Tell the board to wipe its stored credentials and return to AP mode. */
    fun forgetBoardWifi() {
        viewModelScope.launch {
            val ok = notificationSender.send(BoardWifiCommands.forget())
            _wifiSendResult.value =
                if (ok) "Display Wi-Fi credentials cleared"
                else "Couldn't reach the display — is it connected?"
        }
    }

    /* ---------------------- Diagnostics ---------------------- */

    fun resetDiagnostics() {
        diagnosticsRepository.reset()
    }

    /* ---------------------- Storage ---------------------- */

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { storageProvider.snapshot() }
        }
    }

    fun clearTripHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storageProvider.clearAllTripsAndTelemetry() }
            refreshStorage()
        }
    }

    fun clearExportsCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storageProvider.clearExportsCache() }
            refreshStorage()
        }
    }

    /* ---------------------- Permissions ---------------------- */

    fun refreshPermissions() {
        _permissions.value = permissionProvider.snapshot()
    }

    private data class CoreBundle(
        val theme: com.example.displayapp.domain.model.ThemeSettings,
        val app:   com.example.displayapp.domain.model.AppSettings,
        val device: com.example.displayapp.data.preferences.SavedDevice?,
        val conn:  com.example.displayapp.domain.model.ConnectionState,
        val diag:  com.example.displayapp.data.diagnostics.DiagnosticsSnapshot
    )
}

class SettingsViewModelFactory(
    private val themeRepository: ThemeRepository,
    private val appPreferences: AppPreferencesRepository,
    private val devicePreferences: DevicePreferences,
    private val vehicleRepository: VehicleRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val storageProvider: StorageInfoProvider,
    private val permissionProvider: PermissionStatusProvider,
    private val notificationSender: PhoneNotificationSender,
    private val onSimulatorModeChange: suspend (Boolean) -> Unit,
    private val onSimulatorScenarioChange: suspend (TelemetryScenario) -> Unit,
    private val onRunNavDemo: () -> Unit,
    private val onStopNavDemo: () -> Unit,
    private val appVersion: String,
    private val appBuildNumber: String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(
                themeRepository, appPreferences, devicePreferences, vehicleRepository,
                diagnosticsRepository, storageProvider, permissionProvider,
                notificationSender,
                onSimulatorModeChange, onSimulatorScenarioChange,
                onRunNavDemo, onStopNavDemo,
                appVersion, appBuildNumber
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
