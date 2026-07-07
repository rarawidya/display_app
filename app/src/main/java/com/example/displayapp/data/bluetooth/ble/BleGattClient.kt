package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Low-level BLE GATT client — owns the socket-level connection to the controller.
 *
 * Owns a single [BluetoothGatt] and runs the connect → MTU → discover → enable-CCCD
 * handshake as a **strictly serialized callback chain** (Android's GATT stack allows
 * only one outstanding operation at a time). Once notifications are enabled, every
 * value delivered on the telemetry TX characteristic is handed to [onBytes] — those
 * bytes feed the unchanged `FrameDecoder` upstream.
 *
 * Not thread-safe; drive it from one coroutine. [onBytes] and [onDisconnected] are
 * invoked on the Android BLE binder thread — keep them non-blocking (they should only
 * `tryEmit` / post work to a scope).
 */
class BleGattClient(
    private val context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onRssi: (Int) -> Unit = {}
) {
    private var gatt: BluetoothGatt? = null

    @Volatile private var subscribed = false

    /**
     * Connect to [device] over LE and subscribe to the telemetry characteristic.
     * Suspends until streaming is ready (true) or the attempt fails/times out (false).
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 15_000): Boolean {
        val ready = CompletableDeferred<Boolean>()

        val cb = object : BluetoothGattCallback() {
            private fun deliver(v: ByteArray) {
                if (v.isNotEmpty()) onBytes(v)
            }

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        Timber.d("BLE connected (status=$status), requesting MTU")
                        if (!g.requestMtu(BleConstants.PREFERRED_MTU)) g.discoverServices()
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        Timber.w("BLE disconnected (status=$status)")
                        if (!ready.isCompleted) ready.complete(false)
                        else if (subscribed) {
                            subscribed = false
                            onDisconnected()
                        }
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Timber.d("BLE MTU=$mtu (status=$status)")
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (!ready.isCompleted) ready.complete(false); return
                }
                val ch = g.getService(BleConstants.SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.TX_CHAR_UUID)
                if (ch == null) {
                    Timber.e("BLE telemetry characteristic ${BleConstants.TX_CHAR_UUID} not found")
                    if (!ready.isCompleted) ready.complete(false); return
                }
                if (!g.setCharacteristicNotification(ch, true)) {
                    Timber.e("BLE: setCharacteristicNotification rejected for ${ch.uuid}")
                    if (!ready.isCompleted) ready.complete(false); return
                }
                val cccd = ch.getDescriptor(BleConstants.CCCD_UUID)
                if (cccd == null) {
                    if (!ready.isCompleted) ready.complete(false); return
                }
                val enable = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") run { cccd.value = enable; g.writeDescriptor(cccd) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == BleConstants.CCCD_UUID && !ready.isCompleted) {
                    subscribed = status == BluetoothGatt.GATT_SUCCESS
                    ready.complete(subscribed)
                }
            }

            // Android 13+ delivers the value directly; older devices read it off the char.
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
                deliver(value)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
                deliver(c.value ?: ByteArray(0))

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
            }
        }

        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (!ok) close()
        return ok
    }

    /** Request the connected link's RSSI; the result arrives via [onRssi]. */
    @SuppressLint("MissingPermission")
    fun readRssi() {
        runCatching { gatt?.readRemoteRssi() }
    }

    fun close() {
        subscribed = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
