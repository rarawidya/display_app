package com.example.displayapp.data.repository

import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
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
    private val tripSessionManager: TripSessionManager,
    private val diagnostics: DiagnosticsRepository
) : VehicleRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _vehicleData = MutableStateFlow(VehicleData())
    override val vehicleData: StateFlow<VehicleData> = _vehicleData.asStateFlow()

    override val connectionState: StateFlow<ConnectionState> = dataSource.connectionState
    override val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = dataSource.discoveredDevices
    override val rssi: StateFlow<Int?> = dataSource.rssi

    // FrameDecoder is the natural choke point for live protocol diagnostics —
    // every byte off the SPP pipe flows through here. Feeding the counters
    // from this single site is what lets the overlay show real CRC / sync /
    // frame numbers instead of placeholder zeros.
    private val frameDecoder = FrameDecoder(
        onFrame = { payload ->
            val current = _vehicleData.value
            val mapped = mapper.map(payload, current)
            if (mapped != null) {
                _vehicleData.value = mapped
                diagnostics.reportFrame()
                maybeAutoStartTrip(mapped)
                tripSessionManager.onTelemetryUpdate(mapped)
            }
        },
        onCrcError = { diagnostics.reportCrcError() },
        onSyncLoss = { diagnostics.reportSyncLoss() }
    )

    // Guards the async startTrip() against a burst of frames opening duplicate
    // trips before the first insert flips isRecording.
    @Volatile
    private var startingTrip = false

    init {
        scope.launch {
            dataSource.incomingData.collect { chunk ->
                frameDecoder.feed(chunk)
            }
        }
        // Finalize the active trip when the link drops for good. RECONNECTING is
        // left alone so a transient stall (watchdog re-scan) doesn't split a ride
        // into two; only a real DISCONNECTED ends recording.
        scope.launch {
            dataSource.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED && tripSessionManager.isRecording) {
                    tripSessionManager.stopTrip(_vehicleData.value)
                }
            }
        }
    }

    /**
     * Auto-record: open a trip the moment the vehicle starts moving on a live
     * link, so the Last Ride card and Logs reflect real rides (nothing else
     * calls [TripSessionManager.startTrip]). Waiting for movement avoids opening
     * an empty trip every time the app merely connects while parked.
     */
    private fun maybeAutoStartTrip(data: VehicleData) {
        if (tripSessionManager.isRecording || startingTrip) return
        if (data.speed <= 0) return
        if (dataSource.connectionState.value != ConnectionState.CONNECTED) return
        startingTrip = true
        scope.launch {
            try {
                tripSessionManager.startTrip(data)
            } finally {
                startingTrip = false
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
