package com.example.displayapp.data.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Locates the controller for a (re)connect.
 *
 * The controller uses a **resolvable-private (rotating) address** and — per the probe
 * — may not advertise its service UUID, so we can neither trust a stored MAC forever
 * nor rely on a `ScanFilter`. Strategy, in order of preference:
 *   1. exact match on [preferAddress] (the address the user just picked, still current);
 *   2. any device advertising [BleConstants.SERVICE_UUID] (works only if the firmware
 *      puts the UUID in the advertisement — see docs/BLE_FIRMWARE_REQUIREMENTS.md §1.3);
 *   3. otherwise null — reconnect can't identify the device without one of the above.
 *
 * Scan is **unfiltered** so case 1 works even when the service UUID isn't advertised.
 */
class BleServiceScanner(private val adapter: BluetoothAdapter?) {

    @SuppressLint("MissingPermission")
    suspend fun findDevice(
        preferAddress: String? = null,
        timeoutMs: Long = 10_000
    ): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val result = CompletableDeferred<BluetoothDevice?>()
        var serviceMatch: BluetoothDevice? = null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (preferAddress != null && r.device.address == preferAddress) {
                    if (!result.isCompleted) result.complete(r.device)
                    return
                }
                val advertisesService = r.scanRecord?.serviceUuids
                    ?.any { it.uuid == BleConstants.SERVICE_UUID } == true
                if (advertisesService && serviceMatch == null) serviceMatch = r.device
            }
            override fun onScanFailed(errorCode: Int) {
                Timber.w("BLE service scan failed: $errorCode")
                if (!result.isCompleted) result.complete(null)
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(null, settings, cb) // unfiltered — see class doc
            withTimeoutOrNull(timeoutMs) { result.await() } ?: serviceMatch
        } catch (e: SecurityException) {
            Timber.e(e, "BLE scan permission denied")
            null
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }
}
