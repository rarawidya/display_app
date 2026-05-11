package com.example.displayapp.data.bluetooth

import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothDataSource {
    val incomingData: SharedFlow<ByteArray>
    val connectionState: StateFlow<ConnectionState>
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>>

    fun startDiscovery()
    fun stopDiscovery()
    suspend fun connect(address: String)
    fun disconnect()
    fun close()
}
