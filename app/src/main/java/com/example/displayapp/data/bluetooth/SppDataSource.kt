package com.example.displayapp.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.example.displayapp.data.bluetooth.connection.ReconnectPolicy
import com.example.displayapp.data.bluetooth.connection.SppSocketClient
import com.example.displayapp.data.bluetooth.scanner.BluetoothScanner
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import kotlinx.coroutines.CancellationException
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
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Production Bluetooth Classic SPP data source.
 *
 * Composes:
 * - [BluetoothScanner] for device discovery
 * - [SppSocketClient] for socket lifecycle and byte stream reading
 * - [ReconnectPolicy] for exponential backoff reconnection
 *
 * Data flow:
 *   BT socket → SppSocketClient.readLoop() → SharedFlow<ByteArray> → Repository
 *
 * Reconnect behavior:
 *   On unexpected disconnect (IOException during read), the source transitions to
 *   RECONNECTING and retries with exponential backoff. Manual disconnect() stops
 *   reconnection permanently until a new connect() call.
 */
@SuppressLint("MissingPermission")
class SppDataSource(private val context: Context) : BluetoothDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scanner: BluetoothScanner? = bluetoothAdapter?.let {
        BluetoothScanner(context, it)
    }

    private var socketClient: SppSocketClient? = null
    private var scanStateJob: Job? = null
    private val reconnectPolicy = ReconnectPolicy()

    private var targetAddress: String? = null
    private var intentionalDisconnect = false

    // A live socket doesn't notice the local adapter being switched off for up to a
    // few seconds (until read() errors or the watchdog trips), and the reconnect
    // loop would then spin uselessly against a dead adapter. Observe the adapter and
    // drop straight to DISCONNECTED the moment it goes off, so the UI matches reality.
    private var adapterReceiverRegistered = false
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handleAdapterOff()
            }
        }
    }

    init {
        runCatching {
            context.registerReceiver(
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            adapterReceiverRegistered = true
        }
    }

    /** Adapter switched off — abort any live session/reconnect and report DISCONNECTED. */
    private fun handleAdapterOff() {
        if (_connectionState.value == ConnectionState.DISCONNECTED && targetAddress == null) return
        Timber.i("Bluetooth adapter turned off — dropping connection")
        connectionJob?.cancel()
        connectionJob = null
        targetAddress = null
        socketClient?.close()
        socketClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Monotonic timestamp (elapsedRealtime) of the last byte chunk received on the
     * active socket. Updated from the read loop and polled by the frame watchdog.
     * `@Volatile` because it is written on the read coroutine and read on the watchdog
     * coroutine (both on [Dispatchers.IO], but not guaranteed the same thread).
     */
    @Volatile
    private var lastDataElapsedMs: Long = 0L

    private val _incomingData = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>>
        get() = scanner?.discoveredDevices ?: MutableStateFlow(emptyList())

    override fun startDiscovery() {
        if (bluetoothAdapter == null) {
            Timber.w("Bluetooth not available on this device")
            return
        }
        _connectionState.value = ConnectionState.SCANNING
        scanner?.startScan()

        // Update state when scan completes. Cancel any prior collector first — a
        // StateFlow collector never completes on its own, so relaunching per scan
        // would accumulate them.
        scanStateJob?.cancel()
        scanStateJob = scope.launch {
            scanner?.isScanning?.collect { scanning ->
                if (!scanning && _connectionState.value == ConnectionState.SCANNING) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    override fun stopDiscovery() {
        scanner?.stopScan()
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun connect(address: String) {
        val adapter = bluetoothAdapter ?: run {
            Timber.e("Cannot connect: Bluetooth adapter unavailable")
            return
        }

        intentionalDisconnect = false
        targetAddress = address
        reconnectPolicy.reset()

        doConnect(adapter, address)
    }

    override fun disconnect() {
        Timber.i("Intentional disconnect")
        intentionalDisconnect = true
        targetAddress = null
        connectionJob?.cancel()
        connectionJob = null
        socketClient?.close()
        socketClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun close() {
        disconnect()
        if (adapterReceiverRegistered) {
            runCatching { context.unregisterReceiver(adapterStateReceiver) }
            adapterReceiverRegistered = false
        }
        scanner?.release()
        scope.cancel()
    }

    private fun doConnect(adapter: BluetoothAdapter, address: String) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            // A disconnect() that lands between handleConnectionLost scheduling this
            // reconnect and it running would otherwise resurrect a session the user
            // explicitly closed. Re-check the flag before doing anything.
            if (intentionalDisconnect) return@launch
            _connectionState.value = ConnectionState.CONNECTING

            val client = SppSocketClient(adapter)
            socketClient = client
            var watchdogJob: Job? = null

            try {
                client.connect(address)
                _connectionState.value = ConnectionState.CONNECTED
                reconnectPolicy.reset()
                Timber.i("Connection established, starting read loop")

                // Arm the frame watchdog before the (blocking) read loop. A BluetoothSocket
                // input stream ignores soTimeout, so a silent link drop (MCU watchdog reset,
                // out-of-range) leaves read() blocked forever and reconnect never fires. The
                // watchdog force-closes the socket on staleness, which surfaces as an
                // IOException out of readLoop and routes into handleConnectionLost().
                lastDataElapsedMs = SystemClock.elapsedRealtime()
                watchdogJob = launch { runFrameWatchdog(client) }

                // Block here reading until stream ends or error
                client.readLoop { chunk ->
                    lastDataElapsedMs = SystemClock.elapsedRealtime()
                    _incomingData.emit(chunk)
                }

                // readLoop returned normally → remote closed connection
                Timber.d("Read loop ended normally")
                handleConnectionLost()

            } catch (e: CancellationException) {
                // Coroutine cancelled (intentional disconnect or scope cancel)
                Timber.d("Connection coroutine cancelled")
                client.close()
                throw e

            } catch (e: IOException) {
                Timber.w(e, "Connection/read error")
                client.close()
                handleConnectionLost()

            } catch (e: Exception) {
                // Any other non-cancellation throwable (e.g. a SecurityException that
                // escaped the socket client) must not crash the supervisor scope —
                // route it through the normal connection-lost path.
                Timber.e(e, "Unexpected connection error")
                client.close()
                handleConnectionLost()

            } finally {
                watchdogJob?.cancel()
            }
        }
    }

    /**
     * Polls the time since the last received chunk and force-closes the socket if it
     * exceeds [FRAME_WATCHDOG_TIMEOUT_MS]. Closing unblocks the read loop with an
     * IOException, which drives the normal reconnect path. Runs as a child of the
     * connection coroutine and is cancelled when that coroutine unwinds.
     */
    private suspend fun runFrameWatchdog(client: SppSocketClient) {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_POLL_INTERVAL_MS)
            val sinceLastData = SystemClock.elapsedRealtime() - lastDataElapsedMs
            if (sinceLastData >= FRAME_WATCHDOG_TIMEOUT_MS) {
                Timber.w("Frame watchdog tripped: no data for ${sinceLastData}ms, forcing socket close")
                client.close()
                return
            }
        }
    }

    private fun handleConnectionLost() {
        socketClient?.close()
        socketClient = null

        if (intentionalDisconnect) return

        val address = targetAddress ?: return
        val adapter = bluetoothAdapter ?: return

        if (!reconnectPolicy.canRetry) {
            Timber.w("Max reconnect attempts reached, giving up")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING
        val delayMs = reconnectPolicy.nextDelayMs()

        connectionJob = scope.launch {
            Timber.d("Scheduling reconnect in ${delayMs}ms")
            delay(delayMs)
            doConnect(adapter, address)
        }
    }

    companion object {
        /**
         * Max silence (ms) tolerated on a connected socket before the watchdog treats
         * the link as dead. Calibrated against the canonical 20 Hz wire rate (~50 ms
         * between frames): 3 s is ~60 missed frames — far beyond any normal jitter, so
         * it never false-trips, while still failing fast enough to start reconnecting.
         */
        private const val FRAME_WATCHDOG_TIMEOUT_MS = 3_000L

        /** How often the watchdog checks staleness. */
        private const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
    }
}
