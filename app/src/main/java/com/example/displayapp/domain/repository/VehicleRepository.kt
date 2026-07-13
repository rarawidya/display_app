package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.DeviceInfo
import com.example.displayapp.domain.model.VehicleData
import kotlinx.coroutines.flow.StateFlow

interface VehicleRepository {
    val vehicleData: StateFlow<VehicleData>
    val connectionState: StateFlow<ConnectionState>
    val availableDevices: StateFlow<List<BluetoothDeviceInfo>>

    /** Live signal strength of the connected link in dBm; null when disconnected. */
    val rssi: StateFlow<Int?>

    /** Vehicle model / firmware from the Device Information Service; null until read. */
    val deviceInfo: StateFlow<DeviceInfo?>

    fun startScan()
    fun stopScan()
    suspend fun connect(address: String)
    fun disconnect()
}
