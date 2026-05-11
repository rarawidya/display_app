package com.example.displayapp.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
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
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException

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
    private val reconnectPolicy = ReconnectPolicy()

    private var targetAddress: String? = null
    private var intentionalDisconnect = false

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

        // Update state when scan completes
        scope.launch {
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
        scanner?.release()
        scope.cancel()
    }

    private fun doConnect(adapter: BluetoothAdapter, address: String) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING

            val client = SppSocketClient(adapter)
            socketClient = client

            try {
                client.connect(address)
                _connectionState.value = ConnectionState.CONNECTED
                reconnectPolicy.reset()
                Timber.i("Connection established, starting read loop")

                // Block here reading until stream ends or error
                client.readLoop { chunk ->
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
}
