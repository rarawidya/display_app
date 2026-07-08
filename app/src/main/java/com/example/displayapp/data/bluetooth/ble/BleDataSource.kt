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
 * Bluetooth **Low Energy** telemetry source — implements the [BluetoothDataSource]
 * contract so everything above the seam (repository, ViewModels, UI, protocol parsing)
 * is unchanged.
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

    @Volatile private var targetAddress: String? = null
    @Volatile private var intentionalDisconnect = false

    @Volatile private var lastDataElapsedMs: Long = 0L
    // Distinguishes "connected but first notification not in yet" from "link stalled",
    // so a slow first frame (CCCD enable + conn-interval negotiation) doesn't false-trip
    // the watchdog into a needless reconnect.
    @Volatile private var firstByteSeen: Boolean = false

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    // Board→phone control events (0xAF05): whole frames, low rate (button taps).
    private val _controlFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val controlFrames: SharedFlow<ByteArray> = _controlFrames.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Discovery for the picker UI still runs through the adapter/BluetoothController;
    // this data source doesn't surface a separate discovered list.
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    // Live link RSSI (dBm), polled off the connected GATT; null while disconnected.
    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    /* ---- adapter-off awareness: drop straight to DISCONNECTED when BT turns off ---- */

    private var adapterReceiverRegistered = false
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                // Post onto the connection scope so adapter-off serializes with the
                // reconnect logic instead of mutating shared state from the binder thread.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    scope.launch { handleAdapterOff() }
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
        _rssi.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Route a command frame to the live GATT link (phone → board, 0xAF07). */
    override suspend fun writeCommand(frame: ByteArray): Boolean =
        gattClient?.writeCommand(frame) ?: false

    override suspend fun writeNav(frame: ByteArray, reliable: Boolean): Boolean =
        gattClient?.writeCommand(frame, BleConstants.NAV_CHAR_UUID, withResponse = reliable) ?: false

    override fun close() {
        disconnect()
        if (adapterReceiverRegistered) {
            runCatching { context.unregisterReceiver(adapterStateReceiver) }
            adapterReceiverRegistered = false
        }
        scope.cancel()
    }

    private fun doConnect() {
        // Tear down any prior attempt first: cancel its coroutine AND close its GATT,
        // so re-entering connect (or a reconnect after a fresh connect) never leaks a
        // BluetoothGatt or leaves a stray callback armed against the old session.
        connectionJob?.cancel()
        gattClient?.close()
        gattClient = null
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
                    firstByteSeen = true
                    _incomingData.tryEmit(chunk)
                },
                onDisconnected = { scope.launch { handleConnectionLost() } },
                onRssi = { _rssi.value = it },
                onControlBytes = { _controlFrames.tryEmit(it) }
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
            firstByteSeen = false
            client.readRssi() // seed the first reading right away
            Timber.i("BLE connected + subscribed to ${BleConstants.TX_CHAR_UUID}")

            runFrameWatchdog(client)
        }
    }

    /** Force-drop a silently stalled link so reconnect fires (BLE can stall too). */
    private suspend fun runFrameWatchdog(client: BleGattClient) {
        var tick = 0
        while (coroutineContext.isActive) {
            delay(WATCHDOG_POLL_INTERVAL_MS)
            if (_connectionState.value != ConnectionState.CONNECTED) return
            // Refresh the link RSSI on a slower cadence than the staleness poll.
            if (++tick % RSSI_POLL_EVERY_N_TICKS == 0) client.readRssi()
            val silent = SystemClock.elapsedRealtime() - lastDataElapsedMs
            // Grace period before the first byte: subscribe + conn-interval negotiation
            // can legitimately delay the first notification past the steady-state timeout.
            val timeout = if (firstByteSeen) FRAME_WATCHDOG_TIMEOUT_MS else INITIAL_DATA_TIMEOUT_MS
            if (silent >= timeout) {
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
        _rssi.value = null // link gone → drop the stale reading
        if (intentionalDisconnect) return

        if (!reconnectPolicy.canRetry) {
            Timber.w("BLE: max reconnect attempts reached")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING
        val delayMs = reconnectPolicy.nextDelayMs()
        // Cancel any prior attempt before scheduling this one, so concurrent loss paths
        // (watchdog / onDisconnected / scan-fail) can't spawn parallel reconnect loops.
        connectionJob?.cancel()
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
        _rssi.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private companion object {
        const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
        const val FRAME_WATCHDOG_TIMEOUT_MS = 3_000L
        const val INITIAL_DATA_TIMEOUT_MS = 8_000L
        // RSSI refresh cadence as a multiple of the 1 s watchdog tick (→ every 2 s).
        const val RSSI_POLL_EVERY_N_TICKS = 2
    }
}
