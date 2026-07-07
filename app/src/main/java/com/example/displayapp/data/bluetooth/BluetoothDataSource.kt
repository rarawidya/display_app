package com.example.displayapp.data.bluetooth

import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothDataSource {
    val incomingData: SharedFlow<ByteArray>
    val connectionState: StateFlow<ConnectionState>
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>>

    /** Live signal strength of the connected link in dBm; null when disconnected. */
    val rssi: StateFlow<Int?>

    fun startDiscovery()
    fun stopDiscovery()
    suspend fun connect(address: String)
    fun disconnect()
    fun close()

    /**
     * Push one complete command frame to the board (phone → board channel).
     * Returns false when the transport can't deliver it — not connected, no
     * command characteristic, or the source has no physical board (simulator).
     * Sources that only receive telemetry may leave the default no-op.
     */
    suspend fun writeCommand(frame: ByteArray): Boolean = false
}
