package com.example.displayapp.presentation.state

import com.example.displayapp.data.bluetooth.controller.AdapterState
import com.example.displayapp.domain.model.ConnectionState

/**
 * Section-aware view of a single device row inside the Bluetooth quick sheet.
 *
 * Section is decided by the ViewModel based on:
 *  - currently connected (== [UiDeviceState.CONNECTED] or [CONNECTING])
 *  - saved auto-connect target ([previouslyConnected])
 *  - bonded list ([paired])
 *  - freshly discovered, not bonded ([available])
 */
data class UiDevice(
    val address: String,
    val name: String,
    val rssi: Int?,
    val state: UiDeviceState
) {
    /** Signal level bucket 0..3, derived from RSSI. Null when no RSSI is available. */
    val signalBars: Int? = rssi?.let { dbm ->
        when {
            dbm >= -60 -> 3
            dbm >= -75 -> 2
            dbm >= -90 -> 1
            else -> 0
        }
    }
}

enum class UiDeviceState {
    /** Bonded or freshly discovered, no active connection attempt. */
    AVAILABLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class BluetoothUiState(
    val adapterState: AdapterState = AdapterState.OFF,
    /**
     * Raw live link state from the repository. Unlike [connected] (non-null only
     * when fully CONNECTED), this preserves CONNECTING/RECONNECTING so headers
     * and popovers can reflect an in-flight connect/reconnect instead of
     * collapsing it to "Disconnected".
     */
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    /** Currently connected device, if any. Shown at the top of the list. */
    val connected: UiDevice? = null,
    /** Last-used auto-connect device that isn't currently connected. */
    val previouslyConnected: UiDevice? = null,
    /** Whether the saved device should auto-reconnect on app launch. */
    val autoConnect: Boolean = true,
    /** Bonded devices (excludes [connected] and [previouslyConnected]). */
    val paired: List<UiDevice> = emptyList(),
    /** ACTION_FOUND results not yet bonded (excludes [connected]). */
    val available: List<UiDevice> = emptyList(),
    val isScanning: Boolean = false,
    /** Address we just kicked off TelemetryService for — shows a spinner on its row. */
    val pendingConnectAddress: String? = null,
    /** Inline error chip text. Cleared via ViewModel.clearError(). */
    val errorMessage: String? = null
) {
    val showEmptyState: Boolean
        get() = adapterState == AdapterState.ON &&
            connected == null &&
            previouslyConnected == null &&
            paired.isEmpty() &&
            available.isEmpty() &&
            !isScanning

    val statusLine: String
        get() = when (adapterState) {
            AdapterState.UNSUPPORTED -> "Bluetooth not available"
            AdapterState.OFF -> "Bluetooth is off"
            AdapterState.TURNING_ON -> "Turning on..."
            AdapterState.TURNING_OFF -> "Turning off..."
            AdapterState.ON -> when {
                connected != null -> "Connected to ${connected.name}"
                isScanning -> "Scanning for devices..."
                previouslyConnected != null -> "Last used: ${previouslyConnected.name}"
                else -> "No device connected"
            }
        }
}
