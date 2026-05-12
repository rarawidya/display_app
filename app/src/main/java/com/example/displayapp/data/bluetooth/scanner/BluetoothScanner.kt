package com.example.displayapp.data.bluetooth.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * Bluetooth device scanner. Discovers nearby Bluetooth Classic devices
 * and exposes results as a StateFlow.
 *
 * Lifecycle:
 *   startScan() → devices appear in [discoveredDevices] → stopScan()
 *
 * Paired (bonded) devices are included immediately when scanning starts.
 * Newly discovered devices are appended as ACTION_FOUND intents arrive.
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val foundDevices = mutableListOf<BluetoothDeviceInfo>()
    private var receiver: BroadcastReceiver? = null

    fun startScan() {
        if (_isScanning.value) return

        Timber.d("Starting Bluetooth scan")
        foundDevices.clear()

        // Show paired devices immediately so the list isn't empty while
        // discovery warms up.
        adapter.bondedDevices?.forEach { device ->
            addDevice(device)
        }
        _discoveredDevices.value = foundDevices.toList()

        val discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = extractDevice(intent) ?: return
                        addDevice(device)
                        _discoveredDevices.value = foundDevices.toList()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Timber.d("Discovery finished, found ${foundDevices.size} devices")
                        _isScanning.value = false
                    }
                }
            }
        }

        receiver = discoveryReceiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

        // Only flip isScanning once the system accepts the request. If
        // startDiscovery() returns false (location services off pre-Android 12,
        // adapter transitioning, etc.) the spinner would otherwise be stuck:
        // the receiver never fires ACTION_DISCOVERY_FINISHED, so isScanning
        // never reverts to false.
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (started) {
            _isScanning.value = true
        } else {
            Timber.w("startDiscovery() returned false — leaving scanner idle")
            unregisterReceiver()
            _isScanning.value = false
        }
    }

    fun stopScan() {
        if (!_isScanning.value && receiver == null) return

        Timber.d("Stopping Bluetooth scan")
        adapter.cancelDiscovery()
        unregisterReceiver()
        _isScanning.value = false
    }

    /**
     * Returns a cold Flow that emits discovered devices as they're found.
     * The flow completes when discovery finishes or is cancelled.
     */
    fun scanAsFlow(): Flow<BluetoothDeviceInfo> = callbackFlow {
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = extractDevice(intent) ?: return
                        val info = BluetoothDeviceInfo(
                            name = device.name ?: "Unknown (${device.address})",
                            address = device.address
                        )
                        trySend(info)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        close()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(scanReceiver, filter)
        adapter.startDiscovery()

        awaitClose {
            adapter.cancelDiscovery()
            context.unregisterReceiver(scanReceiver)
        }
    }

    fun release() {
        stopScan()
    }

    private fun addDevice(device: BluetoothDevice) {
        val info = BluetoothDeviceInfo(
            name = device.name ?: "Unknown (${device.address})",
            address = device.address
        )
        if (foundDevices.none { it.address == info.address }) {
            foundDevices.add(info)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) { }
            receiver = null
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
