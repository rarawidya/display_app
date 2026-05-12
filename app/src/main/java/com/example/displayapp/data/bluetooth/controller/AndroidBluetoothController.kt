package com.example.displayapp.data.bluetooth.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@SuppressLint("MissingPermission")
class AndroidBluetoothController(private val context: Context) : BluetoothController {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    private val _adapterState = MutableStateFlow(initialAdapterState())
    override val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    private val _paired = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val pairedDevices: StateFlow<List<DiscoveredDevice>> = _paired.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _events = MutableSharedFlow<BluetoothControllerEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<BluetoothControllerEvent> = _events.asSharedFlow()

    private val foundByAddress = linkedMapOf<String, DiscoveredDevice>()
    private var receiversRegistered = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            _adapterState.value = mapAdapterState(state)
            when (_adapterState.value) {
                AdapterState.ON -> refreshPaired()
                AdapterState.OFF, AdapterState.TURNING_OFF -> {
                    foundByAddress.clear()
                    _discovered.value = emptyList()
                    _paired.value = emptyList()
                    _isScanning.value = false
                }
                else -> Unit
            }
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = extractDevice(intent) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .let { if (it == Short.MIN_VALUE) null else it.toInt() }
                    val info = device.toDiscovered(rssi)
                    val existing = foundByAddress[info.address]
                    // Keep the strongest RSSI / latest name if device re-broadcasts
                    val merged = if (existing != null) {
                        existing.copy(
                            name = info.name.takeUnless { it.startsWith("Unknown") } ?: existing.name,
                            rssi = rssi ?: existing.rssi,
                            isBonded = info.isBonded
                        )
                    } else info
                    foundByAddress[info.address] = merged
                    _discovered.value = foundByAddress.values
                        .sortedWith(compareByDescending<DiscoveredDevice> { it.isBonded }
                            .thenByDescending { it.rssi ?: -127 })
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _isScanning.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isScanning.value = false
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = extractDevice(intent) ?: return
            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE
            )
            refreshPaired()
            when (bondState) {
                BluetoothDevice.BOND_BONDED ->
                    _events.tryEmit(BluetoothControllerEvent.BondChanged(device.address, true))
                BluetoothDevice.BOND_NONE ->
                    _events.tryEmit(BluetoothControllerEvent.BondChanged(device.address, false))
            }
        }
    }

    init {
        if (adapter != null) {
            registerReceivers()
            refreshPaired()
        } else {
            Timber.w("BluetoothAdapter unavailable — controller in UNSUPPORTED state")
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        // System-protected broadcasts; using RECEIVER_NOT_EXPORTED keeps us correct
        // on Android 14+ which requires explicit export specs for dynamic receivers.
        ContextCompat.registerReceiver(
            context, adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context, discoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiversRegistered = true
    }

    override fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            _events.tryEmit(BluetoothControllerEvent.Error("Turn on Bluetooth to scan"))
            return
        }
        // Pre-Android 12 (and Android 12+ without neverForLocation), discovery
        // requires location *services* to be on. Catch this before calling
        // startDiscovery() so the error message is actionable.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            _events.tryEmit(BluetoothControllerEvent.Error("Turn on Location to scan"))
            return
        }
        if (a.isDiscovering) return
        foundByAddress.clear()
        _discovered.value = emptyList()
        val started = runCatching { a.startDiscovery() }.getOrDefault(false)
        if (!started) {
            _events.tryEmit(BluetoothControllerEvent.Error("Couldn't start scan — try again"))
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    override fun stopScan() {
        val a = adapter ?: return
        if (a.isDiscovering) {
            runCatching { a.cancelDiscovery() }
        }
        _isScanning.value = false
    }

    override fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    override fun disableIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun forget(address: String): Boolean {
        val a = adapter ?: return false
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return false
        return try {
            // removeBond() is @hide but reachable via reflection on all current Android versions.
            val method = device.javaClass.getMethod("removeBond")
            val result = (method.invoke(device) as? Boolean) ?: false
            if (result) refreshPaired()
            else _events.tryEmit(BluetoothControllerEvent.Error("Couldn't forget device"))
            result
        } catch (t: Throwable) {
            Timber.e(t, "removeBond reflection failed")
            _events.tryEmit(BluetoothControllerEvent.Error("Forget not supported on this device"))
            false
        }
    }

    override fun refreshPaired() {
        val a = adapter
        if (a == null || a.state != BluetoothAdapter.STATE_ON) {
            _paired.value = emptyList()
            return
        }
        val list = runCatching { a.bondedDevices }.getOrNull() ?: emptySet()
        _paired.value = list
            .map { it.toDiscovered(rssi = null) }
            .sortedBy { it.name.lowercase() }
    }

    override fun release() {
        if (receiversRegistered) {
            runCatching { context.unregisterReceiver(adapterReceiver) }
            runCatching { context.unregisterReceiver(discoveryReceiver) }
            runCatching { context.unregisterReceiver(bondReceiver) }
            receiversRegistered = false
        }
        adapter?.let { if (it.isDiscovering) runCatching { it.cancelDiscovery() } }
    }

    private fun initialAdapterState(): AdapterState = when {
        adapter == null -> AdapterState.UNSUPPORTED
        adapter.isEnabled -> AdapterState.ON
        else -> AdapterState.OFF
    }

    private fun mapAdapterState(state: Int): AdapterState = when (state) {
        BluetoothAdapter.STATE_ON -> AdapterState.ON
        BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
        BluetoothAdapter.STATE_OFF -> AdapterState.OFF
        BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
        else -> _adapterState.value
    }

    private fun BluetoothDevice.toDiscovered(rssi: Int?): DiscoveredDevice {
        val raw = runCatching { name }.getOrNull()
        val resolvedName = raw?.takeIf { it.isNotBlank() } ?: "Unknown ($address)"
        return DiscoveredDevice(
            address = address,
            name = resolvedName,
            isBonded = runCatching { bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
            rssi = rssi
        )
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
