package com.example.displayapp.data.bluetooth

import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A [BluetoothDataSource] with a **stable identity** that delegates to a swappable
 * underlying source (simulator ↔ real SPP).
 *
 * Why this exists: [com.example.displayapp.data.repository.VehicleRepositoryImpl] and
 * every ViewModel capture the data source once. If the container simply replaced its
 * field, the repository would keep talking to the *original* instance forever — so
 * "switch to the real device" silently kept running the simulator, and disconnecting
 * the real link never moved the UI off CONNECTED. This facade fixes that: the
 * repository holds one stable object whose flows always mirror whichever delegate is
 * currently active. [swap] re-points the delegate and re-wires the mirror flows.
 *
 * All state observers ([connectionState], [incomingData], [discoveredDevices]) read
 * from this facade's own flows, which are fed by the active delegate. Commands
 * ([connect], [disconnect], discovery) forward to the active delegate.
 */
class SwitchableDataSource(
    initialDelegate: BluetoothDataSource
) : BluetoothDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var delegate: BluetoothDataSource = initialDelegate

    private var mirrorJobs: MutableList<Job> = mutableListOf()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    private val _controlFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val controlFrames: SharedFlow<ByteArray> = _controlFrames.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    init {
        wire(initialDelegate)
    }

    /** Current delegate (for the container to decide whether a swap is needed). */
    val active: BluetoothDataSource get() = delegate

    /**
     * Replace the underlying source. The old delegate is disconnected + closed, the
     * mirrored state is reset to a clean DISCONNECTED, and the new delegate's flows
     * are wired through. Safe to call from any thread; synchronized to serialize swaps.
     */
    @Synchronized
    fun swap(newDelegate: BluetoothDataSource) {
        if (newDelegate === delegate) return
        Timber.i("Swapping data source ${delegate::class.simpleName} -> ${newDelegate::class.simpleName}")
        val old = delegate
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs = mutableListOf()
        // Tear the old link down so its socket/coroutines don't linger.
        runCatching { old.disconnect() }
        runCatching { old.close() }

        delegate = newDelegate
        // Present a clean slate until the new delegate reports its own state.
        _connectionState.value = ConnectionState.DISCONNECTED
        _discoveredDevices.value = emptyList()
        _rssi.value = null
        _deviceInfo.value = null
        wire(newDelegate)
    }

    private fun wire(d: BluetoothDataSource) {
        mirrorJobs.add(scope.launch { d.connectionState.collect { _connectionState.value = it } })
        mirrorJobs.add(scope.launch { d.incomingData.collect { _incomingData.emit(it) } })
        mirrorJobs.add(scope.launch { d.controlFrames.collect { _controlFrames.emit(it) } })
        mirrorJobs.add(scope.launch { d.discoveredDevices.collect { _discoveredDevices.value = it } })
        mirrorJobs.add(scope.launch { d.rssi.collect { _rssi.value = it } })
        mirrorJobs.add(scope.launch { d.deviceInfo.collect { _deviceInfo.value = it } })
    }

    override fun startDiscovery() = delegate.startDiscovery()
    override fun stopDiscovery() = delegate.stopDiscovery()
    override suspend fun connect(address: String) = delegate.connect(address)
    override fun disconnect() = delegate.disconnect()
    override suspend fun writeCommand(frame: ByteArray): Boolean = delegate.writeCommand(frame)
    override suspend fun writeNav(frame: ByteArray, reliable: Boolean): Boolean =
        delegate.writeNav(frame, reliable)

    override fun close() {
        mirrorJobs.forEach { it.cancel() }
        runCatching { delegate.close() }
        scope.cancel()
    }
}
