package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Locates the controller for a (re)connect by scanning for its advertised service
 * UUID [BleConstants.SERVICE_UUID] (`0xAF00`).
 *
 * Per the firmware spec (`docs/EVDISPLAY_BLE_COMMUNICATION.md`) the board advertises
 * `0xAF00` (AD type 0x03) with a **public, stable** address, so:
 *  - the scan is **filtered** on `0xAF00` — which (unlike an unfiltered scan) keeps
 *    delivering results while the screen is off, so background reconnect works;
 *  - every result is a controller, so we take the [preferAddress] match if present
 *    (stable MAC ⇒ reliable), otherwise the strongest signal.
 */
class BleServiceScanner(private val adapter: BluetoothAdapter?) {

    @SuppressLint("MissingPermission")
    suspend fun findDevice(
        preferAddress: String? = null,
        timeoutMs: Long = 10_000
    ): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val result = CompletableDeferred<BluetoothDevice?>()
        var best: ScanResult? = null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                // Filter guarantees r advertises 0xAF00 → it is a controller.
                if (preferAddress != null && r.device.address == preferAddress) {
                    if (!result.isCompleted) result.complete(r.device)
                    return
                }
                if (best == null || r.rssi > best!!.rssi) best = r
            }
            override fun onScanFailed(errorCode: Int) {
                Timber.w("BLE service scan failed: $errorCode")
                if (!result.isCompleted) result.complete(null)
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(listOf(filter), settings, cb)
            withTimeoutOrNull(timeoutMs) { result.await() } ?: best?.device
        } catch (e: SecurityException) {
            Timber.e(e, "BLE scan permission denied")
            null
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }
}
