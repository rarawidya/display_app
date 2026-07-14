package com.innodrive.evdash.data.bluetooth.controller

import android.content.Intent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter-level Bluetooth surface for the quick-settings sheet.
 *
 * Separate from [com.innodrive.evdash.data.bluetooth.BluetoothDataSource], which
 * is the SPP/RFCOMM connection pipeline. This controller owns the *adapter* and
 * *bond* concerns that the sheet needs: power state, paired list, discovery with
 * RSSI, and forget-device.
 *
 * Lifecycle:
 *  - Receivers register on construction, unregister via [release].
 *  - One instance per process — wired through [com.innodrive.evdash.di.AppContainer].
 */
enum class AdapterState {
    /** Device has no BluetoothAdapter (emulator without BT, etc.). */
    UNSUPPORTED,
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF;

    val isOn: Boolean get() = this == ON
    val isTransitioning: Boolean get() = this == TURNING_ON || this == TURNING_OFF
}

data class DiscoveredDevice(
    val address: String,
    val name: String,
    val isBonded: Boolean,
    /** dBm — null if not provided by ACTION_FOUND (paired list entries are always null). */
    val rssi: Int? = null
)

/**
 * One-shot events the controller emits to the ViewModel.
 * Errors here surface as in-sheet toasts; bond changes invalidate cached state.
 */
sealed interface BluetoothControllerEvent {
    data class Error(val message: String) : BluetoothControllerEvent
    data class BondChanged(val address: String, val bonded: Boolean) : BluetoothControllerEvent
}

interface BluetoothController {
    val adapterState: StateFlow<AdapterState>
    val pairedDevices: StateFlow<List<DiscoveredDevice>>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val isScanning: StateFlow<Boolean>
    val events: SharedFlow<BluetoothControllerEvent>

    fun startScan()
    fun stopScan()

    /** Intent the UI must launch via an Activity result API. Granted == adapter on. */
    fun enableIntent(): Intent

    /** Android 13+ blocked programmatic disable — this opens the Bluetooth settings panel. */
    fun disableIntent(): Intent

    /** Removes the bond on this device. Hidden API (reflection) — returns false if unavailable. */
    fun forget(address: String): Boolean

    /** Re-read [android.bluetooth.BluetoothAdapter.getBondedDevices]. */
    fun refreshPaired()

    /** Unregister receivers + cancel discovery. Safe to call multiple times. */
    fun release()
}
