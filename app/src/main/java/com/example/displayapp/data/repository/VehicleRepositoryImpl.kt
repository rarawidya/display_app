package com.example.displayapp.data.repository

import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.persistence.TripSessionManager
import com.example.displayapp.data.protocol.FrameDecoder
import com.example.displayapp.data.protocol.TelemetryMapper
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VehicleRepositoryImpl(
    private val dataSource: BluetoothDataSource,
    private val mapper: TelemetryMapper,
    private val tripSessionManager: TripSessionManager
) : VehicleRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _vehicleData = MutableStateFlow(VehicleData())
    override val vehicleData: StateFlow<VehicleData> = _vehicleData.asStateFlow()

    override val connectionState: StateFlow<ConnectionState> = dataSource.connectionState
    override val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = dataSource.discoveredDevices

    private val frameDecoder = FrameDecoder { payload ->
        val current = _vehicleData.value
        val mapped = mapper.map(payload, current)
        if (mapped != null) {
            _vehicleData.value = mapped
            // Feed to trip recording if active
            tripSessionManager.onTelemetryUpdate(mapped)
        }
    }

    init {
        scope.launch {
            dataSource.incomingData.collect { chunk ->
                frameDecoder.feed(chunk)
            }
        }
    }

    override fun startScan() {
        dataSource.startDiscovery()
    }

    override fun stopScan() {
        dataSource.stopDiscovery()
    }

    override suspend fun connect(address: String) {
        frameDecoder.reset()
        dataSource.connect(address)
    }

    override fun disconnect() {
        dataSource.disconnect()
    }
}
