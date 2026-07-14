package com.innodrive.evdash.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.innodrive.evdash.data.preferences.DevicePreferences
import com.innodrive.evdash.data.preferences.SavedDevice
import com.innodrive.evdash.domain.model.BluetoothDeviceInfo
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.repository.VehicleRepository
import com.innodrive.evdash.service.TelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceViewModel(
    private val repository: VehicleRepository,
    private val devicePreferences: DevicePreferences
) : ViewModel() {

    private val _savedDevice = MutableStateFlow<SavedDevice?>(null)
    val savedDevice: StateFlow<SavedDevice?> = _savedDevice.asStateFlow()

    val devices: StateFlow<List<BluetoothDeviceInfo>> = repository.availableDevices

    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    val uiState: StateFlow<DeviceScreenState> = combine(
        repository.availableDevices,
        repository.connectionState,
        _savedDevice
    ) { devices, connState, saved ->
        DeviceScreenState(
            devices = devices,
            connectionState = connState,
            savedDevice = saved,
            isScanning = connState == ConnectionState.SCANNING
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DeviceScreenState()
    )

    init {
        viewModelScope.launch {
            devicePreferences.lastDevice.collect { saved ->
                _savedDevice.value = saved
            }
        }
    }

    fun startScan() { repository.startScan() }
    fun stopScan() { repository.stopScan() }

    fun connectViaService(context: Context, address: String, name: String) {
        val intent = TelemetryService.startIntent(context, address, name)
        startForegroundService(context, intent)
    }

    fun disconnectViaService(context: Context) {
        context.startService(TelemetryService.stopIntent(context))
    }

    fun autoConnectViaService(context: Context) {
        val saved = _savedDevice.value ?: return
        if (!saved.autoConnect) return
        val intent = TelemetryService.startIntent(context, saved.address, saved.name)
        startForegroundService(context, intent)
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            devicePreferences.setAutoConnect(enabled)
        }
    }

    fun forgetDevice() {
        viewModelScope.launch {
            devicePreferences.clear()
            _savedDevice.value = null
        }
    }

    private fun startForegroundService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

data class DeviceScreenState(
    val devices: List<BluetoothDeviceInfo> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val savedDevice: SavedDevice? = null,
    val isScanning: Boolean = false
)
