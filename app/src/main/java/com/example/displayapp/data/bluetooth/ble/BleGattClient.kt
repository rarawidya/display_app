package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Command-write (phone → board, 0xAF07) serialization. GATT allows one
    // outstanding op, so a Mutex gates concurrent writeCommand() calls and the
    // in-flight write's completion is delivered via onCharacteristicWrite.
    private val writeMutex = Mutex()
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null

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

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (c.uuid == BleConstants.RX_CHAR_UUID) {
                    pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
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

    /**
     * Write one complete command frame to the RX characteristic (`0xAF07`) — the
     * phone → board channel (docs/APP-NOTIFICATION-INTEGRATION.md §1). One write =
     * one whole frame (the board does not reassemble), so [bytes] must be the full
     * `[0xAA][LEN][payload][CRC]` frame and fit the negotiated MTU. Suspends until
     * the stack confirms the write (via onCharacteristicWrite) or [timeoutMs] lapses.
     *
     * Returns false if the link isn't ready, the characteristic is missing, the
     * stack rejects the write, or it times out.
     */
    @SuppressLint("MissingPermission")
    suspend fun writeCommand(bytes: ByteArray, timeoutMs: Long = 3_000): Boolean {
        val g = gatt ?: return false
        if (!subscribed) return false
        val ch = g.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.RX_CHAR_UUID)
        if (ch == null) {
            Timber.w("BLE: RX characteristic ${BleConstants.RX_CHAR_UUID} not found — cannot push")
            return false
        }
        return writeMutex.withLock {
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
            val ok = if (started) (withTimeoutOrNull(timeoutMs) { done.await() } ?: false) else false
            pendingWrite = null
            ok
        }
    }

    fun close() {
        subscribed = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
