package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.example.displayapp.data.protocol.FrameDecoder
import com.example.displayapp.data.protocol.TelemetryMapper
import com.example.displayapp.domain.model.VehicleData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Read-only BLE **diagnostic probe** used to discover — and confirm — the
 * controller's GATT profile from real hardware, before any transport code is
 * written. It does NOT touch the live SPP telemetry pipeline.
 *
 * Three operations:
 *  - [scan]   — LE scan for advertising peripherals.
 *  - [probe]  — connect, discover the GATT table, list services/characteristics.
 *  - [listen] — subscribe to a NOTIFY characteristic and run the bytes through the
 *    real [FrameDecoder] + [TelemetryMapper] to *prove* it carries valid telemetry.
 */
class BleGattProbe(private val context: Context) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter

    data class ScanEntry(
        val name: String,
        val address: String,
        val rssi: Int,
        val serviceUuids: List<String>
    )

    /** A concrete (service, characteristic) pair the user can subscribe to. */
    data class CharRef(val service: UUID, val characteristic: UUID)

    data class ProbeResult(val report: String, val telemetryCandidates: List<CharRef>)

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /* ------------------------------- scan -------------------------------- */

    @SuppressLint("MissingPermission")
    suspend fun scan(durationMs: Long = 5_000): List<ScanEntry> {
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, ScanEntry>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val rec = result.scanRecord
                val name = rec?.deviceName ?: runCatching { dev.name }.getOrNull() ?: "(unnamed)"
                val uuids = rec?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
                found[dev.address] = ScanEntry(name, dev.address, result.rssi, uuids)
            }
            override fun onScanFailed(errorCode: Int) { Timber.w("BLE scan failed: error=$errorCode") }
        }
        return try {
            scanner.startScan(cb)
            delay(durationMs)
            found.values.sortedByDescending { it.rssi }
        } catch (e: SecurityException) {
            Timber.e(e, "BLE scan permission denied")
            emptyList()
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    /* ------------------------------- probe ------------------------------- */

    @SuppressLint("MissingPermission")
    suspend fun probe(address: String, timeoutMs: Long = 15_000): ProbeResult {
        val adapter = this.adapter ?: return ProbeResult("No Bluetooth adapter on this device.", emptyList())
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return ProbeResult("Invalid address: $address", emptyList())

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ProbeResult> { cont ->
                val sb = StringBuilder()
                sb.appendLine("Device:  ${runCatching { device.name }.getOrNull() ?: "(unnamed)"}  [$address]")
                sb.appendLine("Type:    ${deviceTypeName(device)}   Bond: ${bondName(device)}   Addr: ${addressTypeHint(address)}")
                sb.appendLine("─────────────────────────────────────────────")

                var gatt: BluetoothGatt? = null
                fun finish(res: ProbeResult) {
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    if (cont.isActive) cont.resume(res)
                }

                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            sb.appendLine("Connected (status=$status). Requesting MTU 247…")
                            if (!g.requestMtu(247)) g.discoverServices()
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            finish(ProbeResult(sb.appendLine("(disconnected, status=$status)").toString(), emptyList()))
                        }
                    }
                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        sb.appendLine("Negotiated MTU: $mtu  (ATT payload ${mtu - 3} B)")
                        g.discoverServices()
                    }
                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(ProbeResult(sb.appendLine("Service discovery failed (status=$status).").toString(), emptyList()))
                            return
                        }
                        sb.append(renderServices(g))
                        val candidates = telemetryCandidates(g)
                        sb.appendLine()
                        sb.append(renderCandidates(candidates))
                        finish(ProbeResult(sb.toString(), candidates))
                    }
                }
                gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                cont.invokeOnCancellation { runCatching { gatt?.close() } }
            }
        }
        return result ?: ProbeResult(
            "Probe timed out after ${timeoutMs}ms — device unreachable, not advertising, or a rotating address that changed. Try Scan first.",
            emptyList()
        )
    }

    /* ------------------------------ listen ------------------------------- */

    /**
     * Subscribe to [ref] for [durationMs] and run every notification through the
     * real [FrameDecoder]/[TelemetryMapper]. Confirms whether this characteristic
     * carries valid framed telemetry. Never throws — results come back as text.
     */
    @SuppressLint("MissingPermission")
    suspend fun listen(address: String, ref: CharRef, durationMs: Long = 5_000): String {
        val adapter = this.adapter ?: return "No Bluetooth adapter."
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return "Invalid address: $address"

        val mapper = TelemetryMapper()
        var frames = 0; var crcErrors = 0; var syncLosses = 0
        var notifs = 0; var totalBytes = 0
        var firstHex: String? = null
        var sample: VehicleData? = null
        val decoder = FrameDecoder(
            onFrame = { payload ->
                frames++
                mapper.map(payload, sample ?: VehicleData())?.let { sample = it }
            },
            onCrcError = { crcErrors++ },
            onSyncLoss = { syncLosses++ }
        )

        val subscribed = CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null
        val cb = object : BluetoothGattCallback() {
            private fun handle(v: ByteArray) {
                notifs++; totalBytes += v.size
                if (firstHex == null) firstHex = v.take(24).hex()
                decoder.feed(v)
            }
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    if (!subscribed.isCompleted) subscribed.complete(false)
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(ref.service)?.getCharacteristic(ref.characteristic)
                if (ch == null) { subscribed.complete(false); return }
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) { subscribed.complete(false); return }
                val enable = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") run { cccd.value = enable; g.writeDescriptor(cccd) }
            }
            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD_UUID && !subscribed.isCompleted) {
                    subscribed.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
            // Android 13+ delivers the value directly.
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = handle(value)
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = handle(c.value ?: ByteArray(0))
        }

        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        try {
            val ok = withTimeoutOrNull(12_000) { subscribed.await() } ?: false
            if (!ok) return "Could not subscribe to ${shortUuid(ref.characteristic)} (connect or CCCD write failed)."
            delay(durationMs)
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }

        return buildString {
            appendLine("Listened ${durationMs}ms on ${shortUuid(ref.characteristic)}")
            appendLine("Notifications: $notifs    Bytes: $totalBytes")
            appendLine("Frames OK: $frames    CRC errors: $crcErrors    Sync losses: $syncLosses")
            firstHex?.let { appendLine("First bytes: $it") }
            sample?.let { appendLine("Decoded → speed=${it.speed}km/h  batt=${it.batteryPercent}%  ${it.voltage}V  rpm=${it.rpm}") }
            appendLine(
                when {
                    frames > 0 && crcErrors == 0 -> "✅ Valid framed telemetry — THIS is the transport channel."
                    frames > 0 -> "◑ Frames decode but some CRC errors — right channel, check framing."
                    notifs > 0 -> "⚠ Data flows but no clean frames — wrong channel or different framing."
                    else -> "✗ No notifications received — wrong characteristic or nothing streaming."
                }
            )
        }
    }

    /* ----------------------------- rendering ----------------------------- */

    private fun renderServices(gatt: BluetoothGatt): String {
        val sb = StringBuilder()
        val services = gatt.services
        if (services.isEmpty()) return "No services discovered.\n"
        services.forEach { svc ->
            sb.appendLine("SERVICE ${svc.uuid}")
            svc.characteristics.forEach { ch ->
                val cccd = if (ch.getDescriptor(CCCD_UUID) != null) "  +CCCD" else ""
                sb.appendLine("   • ${ch.uuid}   [${props(ch)}]$cccd")
            }
        }
        return sb.toString()
    }

    /**
     * Notify/indicate characteristics that could be the telemetry stream, with
     * well-known noise services (GAP/GATT, Google Fast Pair) excluded and
     * pure-NOTIFY channels ranked first.
     */
    private fun telemetryCandidates(gatt: BluetoothGatt): List<CharRef> =
        gatt.services
            .filter { shortHex(it.uuid) !in NOISE_SERVICES }
            .flatMap { svc ->
                svc.characteristics
                    .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    .map { svc to it }
            }
            .sortedBy { (_, ch) -> if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) 1 else 0 }
            .map { (svc, ch) -> CharRef(svc.uuid, ch.uuid) }

    private fun renderCandidates(candidates: List<CharRef>): String {
        if (candidates.isEmpty()) return "⚠ No NOTIFY/INDICATE characteristic (excluding Fast Pair/GAP) — telemetry channel unclear.\n"
        val sb = StringBuilder("Telemetry TX candidate(s) — tap Listen to confirm:\n")
        candidates.forEach { sb.appendLine("   ${shortUuid(it.service)} / ${shortUuid(it.characteristic)}") }
        return sb.toString()
    }

    private fun props(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        return buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }.joinToString("|").ifEmpty { "—" }
    }

    private fun deviceTypeName(d: BluetoothDevice): String = when (runCatching { d.type }.getOrNull()) {
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
        else -> "UNKNOWN"
    }

    private fun bondName(d: BluetoothDevice): String = when (runCatching { d.bondState }.getOrNull()) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        else -> "NONE"
    }

    /**
     * Public vs random from the two MSBs of the address MSB: 11=static-random,
     * 01=resolvable-private (rotates), 00=non-resolvable-private, public otherwise.
     * Advisory — confirm against the firmware spec.
     */
    private fun addressTypeHint(address: String): String {
        val msb = address.substringBefore(':').toIntOrNull(16) ?: return "unknown"
        return when ((msb shr 6) and 0x3) {
            0b11 -> "RANDOM-static"
            0b01 -> "RANDOM-resolvable (rotates!)"
            0b00 -> "RANDOM-non-resolvable"
            else -> "PUBLIC"
        }
    }

    /** "0000af08-…-34fb" → "0xAF08"; otherwise the first UUID segment. */
    private fun shortUuid(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
            "0x${s.substring(4, 8).uppercase()}"
        else s.substringBefore('-')
    }

    private fun shortHex(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).lowercase() else ""
    }

    private fun List<Byte>.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Generic Access, Generic Attribute, Google Fast Pair — never telemetry.
        private val NOISE_SERVICES = setOf("1800", "1801", "fe2c")
    }
}
