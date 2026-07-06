package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.bluetooth.connection.ReconnectPolicy
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Bluetooth **Low Energy** telemetry source — the GATT-based sibling of `SppDataSource`,
 * implementing the same [BluetoothDataSource] contract so everything above the seam
 * (repository, ViewModels, UI, protocol parsing) is unchanged.
 *
 * Flow: scan for [BleConstants.SERVICE_UUID] → [BleGattClient] connect + subscribe →
 * notification bytes → [incomingData] → the same `FrameDecoder` upstream.
 *
 * Reconnect is **by re-scanning the service UUID** (the controller's address rotates),
 * reusing [ReconnectPolicy]'s backoff. A staleness watchdog force-drops a silently
 * stalled link, and an adapter-off receiver reports DISCONNECTED immediately — same
 * robustness contract as the SPP path.
 */
@SuppressLint("MissingPermission")
class BleDataSource(private val context: Context) : BluetoothDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = BleServiceScanner(bluetoothAdapter)

    private var gattClient: BleGattClient? = null
    private var connectionJob: Job? = null
    private val reconnectPolicy = ReconnectPolicy()

    private var targetAddress: String? = null
    private var intentionalDisconnect = false

    @Volatile private var lastDataElapsedMs: Long = 0L

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Discovery for the picker UI still runs through the adapter/BluetoothController;
    // this data source doesn't surface a separate discovered list.
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    /* ---- adapter-off awareness (same contract as SppDataSource) ---- */

    private var adapterReceiverRegistered = false
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handleAdapterOff()
            }
        }
    }

    init {
        runCatching {
            context.registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            adapterReceiverRegistered = true
        }
    }

    override fun startDiscovery() { /* handled by BluetoothController for the picker */ }
    override fun stopDiscovery() { /* no-op */ }

    override suspend fun connect(address: String) {
        if (bluetoothAdapter == null) {
            Timber.e("BLE connect: no adapter")
            return
        }
        intentionalDisconnect = false
        targetAddress = address.takeIf { it.isNotBlank() }
        reconnectPolicy.reset()
        doConnect()
    }

    override fun disconnect() {
        Timber.i("BLE intentional disconnect")
        intentionalDisconnect = true
        targetAddress = null
        connectionJob?.cancel()
        connectionJob = null
        gattClient?.close()
        gattClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun close() {
        disconnect()
        if (adapterReceiverRegistered) {
            runCatching { context.unregisterReceiver(adapterStateReceiver) }
            adapterReceiverRegistered = false
        }
        scope.cancel()
    }

    private fun doConnect() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            if (intentionalDisconnect) return@launch
            _connectionState.value = ConnectionState.CONNECTING

            // Scan for the service UUID (rotating address ⇒ can't trust a stored MAC).
            val device = scanner.findDevice(preferAddress = targetAddress)
            if (device == null) {
                Timber.w("BLE: controller not found in scan")
                handleConnectionLost()
                return@launch
            }
            targetAddress = device.address

            val client = BleGattClient(
                context = context,
                onBytes = { chunk ->
                    lastDataElapsedMs = SystemClock.elapsedRealtime()
                    _incomingData.tryEmit(chunk)
                },
                onDisconnected = { scope.launch { handleConnectionLost() } }
            )
            gattClient = client

            val ok = client.connect(device)
            if (!ok) {
                handleConnectionLost()
                return@launch
            }

            _connectionState.value = ConnectionState.CONNECTED
            reconnectPolicy.reset()
            lastDataElapsedMs = SystemClock.elapsedRealtime()
            Timber.i("BLE connected + subscribed to ${BleConstants.TX_CHAR_UUID}")

            runFrameWatchdog(client)
        }
    }

    /** Force-drop a silently stalled link so reconnect fires (BLE can stall too). */
    private suspend fun runFrameWatchdog(client: BleGattClient) {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_POLL_INTERVAL_MS)
            if (_connectionState.value != ConnectionState.CONNECTED) return
            val silent = SystemClock.elapsedRealtime() - lastDataElapsedMs
            if (silent >= FRAME_WATCHDOG_TIMEOUT_MS) {
                Timber.w("BLE frame watchdog tripped (${silent}ms silent) — dropping")
                client.close()
                handleConnectionLost()
                return
            }
        }
    }

    private fun handleConnectionLost() {
        gattClient?.close()
        gattClient = null
        if (intentionalDisconnect) return

        if (!reconnectPolicy.canRetry) {
            Timber.w("BLE: max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING
        val delayMs = reconnectPolicy.nextDelayMs()
        connectionJob = scope.launch {
            delay(delayMs)
            doConnect()
        }
    }

    private fun handleAdapterOff() {
        if (_connectionState.value == ConnectionState.DISCONNECTED && targetAddress == null) return
        Timber.i("BLE: adapter off — dropping connection")
        connectionJob?.cancel()
        connectionJob = null
        targetAddress = null
        gattClient?.close()
        gattClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private companion object {
        const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
        const val FRAME_WATCHDOG_TIMEOUT_MS = 3_000L
    }
}
