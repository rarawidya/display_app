package com.innodrive.evdash.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.innodrive.evdash.domain.model.DeviceInfo
import java.util.UUID
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
    private val onRssi: (Int) -> Unit = {},
    /** Frames from the optional control uplink (0xAF05, board→phone button events). */
    private val onControlBytes: (ByteArray) -> Unit = {},
    /** Device Information Service read result (model / firmware), once per connect. */
    private val onDeviceInfo: (DeviceInfo) -> Unit = {}
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

        // Device Information Service reads run as the final handshake step (after
        // the CCCD subscribes, before completing `ready`) so no app-initiated write
        // can collide with them — the caller is still suspended in connect() and the
        // connection state hasn't flipped to CONNECTED yet. Absent service/chars or a
        // failed read are skipped; DIS is optional metadata, never a connect blocker.
        val disValues = HashMap<UUID, String>()
        val disQueue = ArrayDeque(listOf(BleConstants.DIS_MODEL_UUID, BleConstants.DIS_FIRMWARE_UUID))

        fun finishDeviceInfo() {
            if (disValues.isNotEmpty()) {
                onDeviceInfo(
                    DeviceInfo(
                        modelNumber = disValues[BleConstants.DIS_MODEL_UUID],
                        firmwareRevision = disValues[BleConstants.DIS_FIRMWARE_UUID]
                    )
                )
            }
            if (!ready.isCompleted) ready.complete(true)
        }

        fun readNextDeviceInfo(g: BluetoothGatt) {
            val next = disQueue.removeFirstOrNull() ?: run { finishDeviceInfo(); return }
            val ch = g.getService(BleConstants.DIS_SERVICE_UUID)?.getCharacteristic(next)
            // Skip an absent char or a read the stack refuses to start.
            if (ch == null || !g.readCharacteristic(ch)) readNextDeviceInfo(g)
        }

        fun beginDeviceInfo(g: BluetoothGatt) {
            if (g.getService(BleConstants.DIS_SERVICE_UUID) == null) { finishDeviceInfo(); return }
            readNextDeviceInfo(g)
        }

        val cb = object : BluetoothGattCallback() {
            private fun deliver(c: BluetoothGattCharacteristic, v: ByteArray) {
                if (v.isEmpty()) return
                if (c.uuid == BleConstants.CONTROL_CHAR_UUID) onControlBytes(v) else onBytes(v)
            }

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        Timber.d("BLE connected (status=$status), requesting MTU")
                        // Invalidate Android's cached GATT attribute table before discovery.
                        // This firmware is open/unbonded and does NOT implement the GATT
                        // Service Changed indication, so Android otherwise reuses a table
                        // cached before 0xAF05 (call control) existed — discovery then
                        // returns from cache with no 0xAF05, the control subscribe is
                        // skipped, and the board drops every Answer/End press. Forcing a
                        // refresh makes the following discovery re-read the live database.
                        refreshServiceCache(g)
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
                if (descriptor.uuid != BleConstants.CCCD_UUID) return
                when (descriptor.characteristic.uuid) {
                    BleConstants.TX_CHAR_UUID -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (!ready.isCompleted) ready.complete(false)
                            return
                        }
                        subscribed = true
                        // Chain the OPTIONAL control-uplink subscribe (0xAF05) before
                        // reporting ready: GATT allows one outstanding op, and a caller
                        // may write a command the moment connect() returns — this CCCD
                        // write must not collide with it. Absent char (old firmware) or
                        // rejected setup → skip; telemetry alone is a successful connect.
                        val ctrl = g.getService(BleConstants.SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.CONTROL_CHAR_UUID)
                        val ctrlCccd = ctrl?.getDescriptor(BleConstants.CCCD_UUID)
                        if (ctrl != null && ctrlCccd != null && g.setCharacteristicNotification(ctrl, true)) {
                            @Suppress("DEPRECATION") run {
                                ctrlCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(ctrlCccd)
                            }
                        } else {
                            beginDeviceInfo(g)
                        }
                    }
                    BleConstants.CONTROL_CHAR_UUID -> {
                        // Optional feature: enable failure downgrades to telemetry-only.
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Timber.w("BLE: control CCCD enable failed (status=$status) — call control off")
                        } else {
                            Timber.i("BLE: control uplink ${BleConstants.CONTROL_CHAR_UUID} subscribed")
                        }
                        beginDeviceInfo(g)
                    }
                }
            }

            // Android 13+ delivers the value directly; older devices read it off the char.
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
                deliver(c, value)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
                deliver(c, c.value ?: ByteArray(0))

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
            }

            private fun handleDeviceInfoRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && v.isNotEmpty()) {
                    // DIS strings are UTF-8; strip surrounding whitespace and NUL padding.
                    val s = v.toString(Charsets.UTF_8).trim { it.isWhitespace() || it.code == 0 }
                    if (s.isNotEmpty()) disValues[c.uuid] = s
                }
                readNextDeviceInfo(g)
            }

            // Android 13+ delivers the value directly; older devices read it off the char.
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                handleDeviceInfoRead(g, c, value, status)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
                handleDeviceInfoRead(g, c, c.value ?: ByteArray(0), status)

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                // Writes are serialized by writeMutex, so there is at most one in-flight
                // op; complete it for either phone→board characteristic (notification/nav).
                if (c.uuid == BleConstants.RX_CHAR_UUID || c.uuid == BleConstants.NAV_CHAR_UUID) {
                    pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (!ok) close()
        return ok
    }

    /**
     * Force-clear Android's cached GATT service database for this connection via the
     * hidden `BluetoothGatt#refresh()`. Reflection is the only access path (no public
     * API); it's best-effort — a failure or a no-op under non-SDK restrictions just
     * leaves the cache in place, degrading to the prior behaviour. The board log line
     * `CCCD call-action notify=1` after a connect is the definitive proof it worked.
     */
    private fun refreshServiceCache(g: BluetoothGatt) {
        val ok = runCatching {
            BluetoothGatt::class.java.getMethod("refresh").invoke(g) as? Boolean
        }.getOrNull()
        Timber.d("BLE: GATT cache refresh -> $ok")
    }

    /** Request the connected link's RSSI; the result arrives via [onRssi]. */
    @SuppressLint("MissingPermission")
    fun readRssi() {
        runCatching { gatt?.readRemoteRssi() }
    }

    /**
     * Write one complete command frame to a phone→board characteristic — the
     * notification channel (`0xAF07`, default) or the navigation channel (`0xAF06`).
     * One write = one whole frame (the board does not reassemble), so [bytes] must be
     * the full `[0xAA][LEN][payload][CRC]` frame and fit the negotiated MTU.
     *
     * [withResponse] picks the GATT write type: `true` = Write (acknowledged, for
     * must-arrive messages) vs `false` = Write-Without-Response (low-latency stream).
     * Either way the call suspends until the stack signals completion via
     * onCharacteristicWrite or [timeoutMs] lapses (Android reports both write types).
     *
     * Returns false if the link isn't ready, the characteristic is absent (e.g. old
     * firmware without `0xAF06`), the stack rejects the write, or it times out.
     */
    @SuppressLint("MissingPermission")
    suspend fun writeCommand(
        bytes: ByteArray,
        characteristic: java.util.UUID = BleConstants.RX_CHAR_UUID,
        withResponse: Boolean = true,
        timeoutMs: Long = 3_000,
    ): Boolean {
        val g = gatt ?: return false
        if (!subscribed) return false
        val ch = g.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(characteristic)
        if (ch == null) {
            Timber.w("BLE: characteristic $characteristic not found — cannot push")
            return false
        }
        val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return writeMutex.withLock {
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = writeType
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
