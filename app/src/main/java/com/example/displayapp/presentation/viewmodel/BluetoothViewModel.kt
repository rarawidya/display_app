package com.example.displayapp.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.bluetooth.controller.AdapterState
import com.example.displayapp.data.bluetooth.controller.BluetoothController
import com.example.displayapp.data.bluetooth.controller.BluetoothControllerEvent
import com.example.displayapp.data.bluetooth.controller.DiscoveredDevice
import com.example.displayapp.data.preferences.DevicePreferences
import com.example.displayapp.data.preferences.SavedDevice
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.repository.VehicleRepository
import com.example.displayapp.presentation.state.BluetoothUiState
import com.example.displayapp.presentation.state.UiDevice
import com.example.displayapp.presentation.state.UiDeviceState
import com.example.displayapp.service.TelemetryService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the quick-settings sheet state. Composes:
 *  - [BluetoothController] for adapter / paired / discovered / scanning,
 *  - [VehicleRepository] for the live SPP connection state,
 *  - [DevicePreferences] for "previously connected" (auto-connect target),
 *  - [TelemetryService] for actual connect/disconnect (foreground service path).
 *
 * The VM never touches the SPP socket directly — it routes through the same
 * service the rest of the app uses, so the notification + lifecycle behave
 * identically whether the user came from the sheet or from DeviceScanScreen.
 */
class BluetoothViewModel(
    private val controller: BluetoothController,
    private val repository: VehicleRepository,
    private val devicePreferences: DevicePreferences,
    private val appContext: Context
) : ViewModel() {

    private val _pendingAddress = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)

    // combine() tops out at 5 args — group the inputs first.
    private data class Adapter(
        val state: AdapterState,
        val paired: List<DiscoveredDevice>,
        val discovered: List<DiscoveredDevice>,
        val scanning: Boolean
    )

    private data class Live(
        val connection: ConnectionState,
        val saved: SavedDevice?,
        val pending: String?,
        val error: String?
    )

    private val adapterFlow = combine(
        controller.adapterState,
        controller.pairedDevices,
        controller.discoveredDevices,
        controller.isScanning
    ) { state, paired, discovered, scanning ->
        Adapter(state, paired, discovered, scanning)
    }

    private val liveFlow = combine(
        repository.connectionState,
        devicePreferences.lastDevice,
        _pendingAddress,
        _error
    ) { conn, saved, pending, err ->
        Live(conn, saved, pending, err)
    }

    val uiState: StateFlow<BluetoothUiState> = combine(adapterFlow, liveFlow) { a, l ->
        build(a, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BluetoothUiState())

    init {
        // Clear the pending spinner once the live connection state catches up.
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                if (_pendingAddress.value == null) return@collect
                if (state == ConnectionState.DISCONNECTED) {
                    _error.value = "Couldn't connect — try again"
                    _pendingAddress.value = null
                } else if (state == ConnectionState.CONNECTED) {
                    _pendingAddress.value = null
                }
            }
        }
        // Forward controller-level errors (forget failed, scan failed, etc.).
        viewModelScope.launch {
            controller.events.collect { event ->
                if (event is BluetoothControllerEvent.Error) _error.value = event.message
            }
        }
    }

    /* --------------- intents --------------- */

    fun startScan() {
        _error.value = null
        controller.startScan()
    }

    fun stopScan() = controller.stopScan()

    fun clearError() { _error.value = null }

    /** Surface an error from a caller that owns its own UI flow (e.g. permission denial). */
    fun surfaceError(message: String) { _error.value = message }

    private var pendingTimeoutJob: Job? = null

    fun connect(address: String, name: String) {
        _error.value = null
        _pendingAddress.value = address
        // Safety net: if the attempt never produces a state transition (e.g. the
        // service can't even reach CONNECTING), clear the spinner so it can't
        // wedge indefinitely. Generous so a slow-but-real connect isn't cut off.
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = viewModelScope.launch {
            delay(PENDING_CONNECT_TIMEOUT_MS)
            if (_pendingAddress.value == address &&
                repository.connectionState.value != ConnectionState.CONNECTED
            ) {
                _pendingAddress.value = null
                _error.value = "Couldn't connect — try again"
            }
        }
        startForegroundService(TelemetryService.startIntent(appContext, address, name))
    }

    fun disconnect() {
        // Clear any in-flight pending spinner so a user-initiated disconnect
        // doesn't trip the collector's "Couldn't connect" error path.
        pendingTimeoutJob?.cancel()
        _pendingAddress.value = null
        appContext.startService(TelemetryService.stopIntent(appContext))
    }

    fun reconnectLast(): Boolean {
        val saved = uiState.value.previouslyConnected ?: return false
        connect(saved.address, saved.name)
        return true
    }

    fun forget(address: String) {
        val state = uiState.value
        if (state.connected?.address == address) disconnect()
        viewModelScope.launch {
            controller.forget(address)
            val saved = devicePreferences.lastDevice.first()
            if (saved?.address == address) devicePreferences.clear()
        }
    }

    /** Persist whether the saved device should auto-reconnect on app launch. */
    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { devicePreferences.setAutoConnect(enabled) }
    }

    /** Called by the UI when the user grants/denies BluetoothAdapter.ACTION_REQUEST_ENABLE. */
    fun onEnableResult(enabled: Boolean) {
        if (!enabled) _error.value = "Bluetooth wasn't enabled"
        else controller.refreshPaired()
    }

    fun enableIntent(): Intent = controller.enableIntent()
    fun disableIntent(): Intent = controller.disableIntent()

    override fun onCleared() {
        controller.stopScan()
        super.onCleared()
    }

    /* --------------- state assembly --------------- */

    private fun build(a: Adapter, l: Live): BluetoothUiState {
        // Only a live CONNECTED link counts as "connected". Treating CONNECTING /
        // RECONNECTING as connected made the sheet + status line read "Connected to X"
        // for the entire multi-minute reconnect window after the device had actually
        // dropped — the exact "still shows connected" symptom users hit.
        val connectedAddress = if (l.connection == ConnectionState.CONNECTED) l.saved?.address else null

        val connectedUi: UiDevice? = connectedAddress?.let { addr ->
            val name = a.paired.firstOrNull { it.address == addr }?.name
                ?: a.discovered.firstOrNull { it.address == addr }?.name
                ?: l.saved?.name
                ?: "Connected device"
            UiDevice(
                address = addr,
                name = name,
                rssi = a.discovered.firstOrNull { it.address == addr }?.rssi,
                state = when (l.connection) {
                    ConnectionState.CONNECTED -> UiDeviceState.CONNECTED
                    ConnectionState.CONNECTING -> UiDeviceState.CONNECTING
                    ConnectionState.RECONNECTING -> UiDeviceState.RECONNECTING
                    else -> UiDeviceState.AVAILABLE
                }
            )
        }

        val pairedAddresses = a.paired.map { it.address }.toHashSet()

        val previouslyConnectedUi: UiDevice? = l.saved
            ?.takeIf { it.address != connectedAddress }
            ?.let { saved ->
                val match = a.paired.firstOrNull { it.address == saved.address }
                    ?: a.discovered.firstOrNull { it.address == saved.address }
                UiDevice(
                    address = saved.address,
                    name = match?.name ?: saved.name,
                    rssi = match?.rssi,
                    state = if (saved.address == l.pending) UiDeviceState.CONNECTING
                            else UiDeviceState.AVAILABLE
                )
            }

        // De-dupe: paired list excludes connected + previouslyConnected
        val pairedUi = a.paired
            .asSequence()
            .filter { it.address != connectedAddress && it.address != previouslyConnectedUi?.address }
            .map { it.toUi(l.pending) }
            .toList()

        // Available list excludes paired + connected (paired already gets its own section)
        val availableUi = a.discovered
            .asSequence()
            .filter { it.address != connectedAddress }
            .filter { it.address !in pairedAddresses }
            .filter { it.address != previouslyConnectedUi?.address }
            .map { it.toUi(l.pending) }
            .toList()

        return BluetoothUiState(
            adapterState = a.state,
            connectionState = l.connection,
            connected = connectedUi,
            previouslyConnected = previouslyConnectedUi,
            autoConnect = l.saved?.autoConnect ?: true,
            paired = pairedUi,
            available = availableUi,
            isScanning = a.scanning,
            pendingConnectAddress = l.pending,
            errorMessage = l.error
        )
    }

    private fun DiscoveredDevice.toUi(pendingAddress: String?): UiDevice = UiDevice(
        address = address,
        name = name,
        rssi = rssi,
        state = if (address == pendingAddress) UiDeviceState.CONNECTING else UiDeviceState.AVAILABLE
    )

    private fun startForegroundService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private companion object {
        // Well beyond a worst-case scan + GATT connect so it only fires when an
        // attempt is genuinely wedged, never mid-connect.
        const val PENDING_CONNECT_TIMEOUT_MS = 45_000L
    }
}

class BluetoothViewModelFactory(
    private val controller: BluetoothController,
    private val repository: VehicleRepository,
    private val devicePreferences: DevicePreferences,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BluetoothViewModel(controller, repository, devicePreferences, appContext) as T
    }
}
