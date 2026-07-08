package com.example.displayapp.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Emits whether the phone's **Wi-Fi hotspot (soft AP)** is currently on — the
 * board joins it as a STA to download maps (docs/BOARD-WIFI-STA-INTEGRATION.md),
 * so the Home/Drive headers surface an at-a-glance indicator.
 *
 * Android has no public hotspot-state API, so this stacks the practical signals:
 *  - live updates from the system's `WIFI_AP_STATE_CHANGED` broadcast (a
 *    protected system broadcast; the action string is stable since API 1);
 *  - the initial value from `WifiManager.getWifiApState()` via reflection
 *    (greylisted, works broadly), falling back to a network-interface heuristic
 *    (the soft AP brings up a dedicated iface — `swlan0` on Samsung, `ap0`/
 *    `softap0` elsewhere — with an IPv4 address).
 * Every layer fails safe to "off".
 */
class HotspotStateMonitor(private val context: Context) {

    val isHotspotActive: Flow<Boolean> = callbackFlow {
        trySend(currentState(context))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(EXTRA_WIFI_AP_STATE, -1) ?: return
                if (state >= 0) trySend(state == WIFI_AP_STATE_ENABLED)
            }
        }
        // EXPORTED so delivery works regardless of the OEM's protected-broadcast
        // list under the API 34+ receiver-flag requirement; the worst a spoofed
        // broadcast can do is mistint an icon (currentState() re-seeds on re-entry).
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private companion object {
        // Hidden-but-stable framework constants (WifiManager).
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val WIFI_AP_STATE_ENABLED = 13

        fun currentState(context: Context): Boolean {
            runCatching {
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val state = wm.javaClass.getDeclaredMethod("getWifiApState")
                    .apply { isAccessible = true }
                    .invoke(wm) as Int
                return state == WIFI_AP_STATE_ENABLED
            }
            // Reflection blocked → look for the soft-AP interface.
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList().any { itf ->
                    itf.isUp && isSoftApName(itf.name.orEmpty()) &&
                        itf.inetAddresses.toList()
                            .any { it is Inet4Address && !it.isLoopbackAddress }
                }
            }.getOrDefault(false)
        }

        fun isSoftApName(name: String): Boolean =
            name.startsWith("ap") || name.startsWith("swlan") ||
                name.startsWith("softap") || name == "wlan1"
    }
}
