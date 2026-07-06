package com.example.displayapp.data.bluetooth.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.example.displayapp.data.bluetooth.ble.BleConstants
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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

/**
 * BLE implementation of [BluetoothController] — the adapter/discovery plane for the
 * pairing sheet, discovering via the **BLE scanner** (`BluetoothLeScanner` +
 * `ScanCallback`).
 *
 * The sheet UI ([com.example.displayapp.presentation.ui.connection.BluetoothManagementSections])
 * and [com.example.displayapp.presentation.viewmodel.BluetoothViewModel] are unchanged
 * — they consume the same interface. Adapter power state, enable/disable, bond
 * changes and forget are identical to the Classic controller (the adapter is shared);
 * only the *discovery* mechanism differs.
 *
 * Discovery is **filtered on service `0xAF00`** (per docs/EVDISPLAY_BLE_COMMUNICATION.md
 * the board advertises it), so the sheet lists the vehicle ("EVdisplay", name from the
 * scan response) rather than every BLE device, and scanning keeps working screen-off.
 * `pairedDevices` surfaces bonded LE/DUAL devices (usually none — open GATT, no bonding).
 */
@SuppressLint("MissingPermission")
class BleController(private val context: Context) : BluetoothController {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private var scanJob: Job? = null

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName
                ?: runCatching { r.device.name }.getOrNull()
                ?: "Unknown (${r.device.address})"
            val info = DiscoveredDevice(
                address = r.device.address,
                name = name,
                isBonded = runCatching { r.device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
                rssi = r.rssi
            )
            val existing = foundByAddress[info.address]
            foundByAddress[info.address] = if (existing != null) {
                existing.copy(
                    name = info.name.takeUnless { it.startsWith("Unknown") } ?: existing.name,
                    rssi = info.rssi ?: existing.rssi,
                    isBonded = info.isBonded
                )
            } else info
            _discovered.value = foundByAddress.values
                .sortedWith(compareByDescending<DiscoveredDevice> { it.isBonded }
                    .thenByDescending { it.rssi ?: -127 })
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.w("BLE scan failed: $errorCode")
            _isScanning.value = false
            _events.tryEmit(BluetoothControllerEvent.Error("Couldn't start scan — try again"))
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            _adapterState.value = mapAdapterState(
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            )
            when (_adapterState.value) {
                AdapterState.ON -> refreshPaired()
                AdapterState.OFF, AdapterState.TURNING_OFF -> {
                    stopScan()
                    foundByAddress.clear()
                    _discovered.value = emptyList()
                    _paired.value = emptyList()
                }
                else -> Unit
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = extractDevice(intent) ?: return
            refreshPaired()
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
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
            Timber.w("BluetoothAdapter unavailable — BLE controller in UNSUPPORTED state")
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        ContextCompat.registerReceiver(
            context, adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
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
        // Pre-Android 12, BLE scanning also needs Location *services* on.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            _events.tryEmit(BluetoothControllerEvent.Error("Turn on Location to scan"))
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        if (_isScanning.value) return
        foundByAddress.clear()
        _discovered.value = emptyList()
        // Filter on the controller's advertised service UUID (0xAF00) so the sheet
        // lists the vehicle ("EVdisplay") rather than every BLE device nearby, and so
        // scanning keeps working with the screen off.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val ok = runCatching { scanner.startScan(listOf(filter), settings, leScanCallback); true }.getOrDefault(false)
        if (!ok) {
            _events.tryEmit(BluetoothControllerEvent.Error("Couldn't start scan — try again"))
            return
        }
        _isScanning.value = true
        // LE scans don't self-terminate — cap the window like Classic inquiry does.
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        val scanner = adapter?.bluetoothLeScanner
        if (_isScanning.value) runCatching { scanner?.stopScan(leScanCallback) }
        _isScanning.value = false
    }

    override fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    override fun disableIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun forget(address: String): Boolean {
        val a = adapter ?: return false
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return false
        // Unbonded BLE devices have nothing to remove — report success so the caller
        // can still clear the saved-device preference.
        if (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE) != BluetoothDevice.BOND_BONDED) {
            return true
        }
        return try {
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
        // Only LE / dual-mode bonds are relevant to a BLE transport.
        val list = runCatching { a.bondedDevices }.getOrNull() ?: emptySet()
        _paired.value = list
            .filter {
                val type = runCatching { it.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
            .map {
                DiscoveredDevice(
                    address = it.address,
                    name = runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() }
                        ?: "Unknown (${it.address})",
                    isBonded = true,
                    rssi = null
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override fun release() {
        stopScan()
        scope.cancel()
        if (receiversRegistered) {
            runCatching { context.unregisterReceiver(adapterReceiver) }
            runCatching { context.unregisterReceiver(bondReceiver) }
            receiversRegistered = false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
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

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val SCAN_DURATION_MS = 12_000L
    }
}
