package com.innodrive.evdash.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.TimeZone

/**
 * Keeps the board's clock correct (docs/TIME-SYNC-INTEGRATION.md). The SG2002 has no
 * RTC/NTP and boots to ~2018, so the phone pushes its wall clock as a `TIME_SYNC`
 * control command (see [TimeSyncCommand]) over the `0xAF07` channel:
 *
 * 1. **On every (re)connect** — the moment [connectionState] transitions to
 *    [ConnectionState.CONNECTED] (which [com.innodrive.evdash.data.bluetooth.ble.BleDataSource]
 *    sets only after the GATT session is ready), so the clock is right immediately —
 *    the board may have just booted to 2018.
 * 2. **Periodically while connected** — every [RESYNC_INTERVAL_MS] (~5 min) to keep
 *    drift out; the board has no other time source. The loop lives inside
 *    `collectLatest`, so a disconnect cancels it and the next connect restarts it.
 * 3. **On a phone timezone/DST/time change** — a broadcast receiver resends with the
 *    new offset (an unsolicited manual change wouldn't otherwise be caught until the
 *    next periodic tick).
 *
 * Gated on the user's "Mirror to vehicle display" toggle
 * ([com.innodrive.evdash.domain.model.AppSettings.notificationRelayEnabled]), the same
 * switch that gates the call/notification relays: with mirroring off, no `TIME_SYNC`
 * frames are sent; flipping it on while connected syncs immediately.
 *
 * There is no board ACK — the implicit confirmation is that the on-screen time becomes
 * correct. App-scoped: owned by the container, [start]ed once from
 * [com.innodrive.evdash.DisplayApp.onCreate]. The wall-clock reads are injectable for
 * tests; production reads `System.currentTimeMillis()` + `TimeZone.getDefault()`.
 */
class TimeSyncCoordinator(
    private val context: Context,
    private val sender: PhoneNotificationSender,
    private val connectionState: StateFlow<ConnectionState>,
    private val prefs: AppPreferencesRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val tzOffsetMinutes: () -> Int = {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    },
) {

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var started = false

    // Mirrored from the settings flow so the clock-change receiver can read the toggle
    // synchronously without collecting again.
    @Volatile
    private var relayEnabled = false

    /** Begin syncing: on-connect + periodic-while-connected + on tz/time change. */
    fun start() {
        if (started) return
        started = true
        val relayEnabledFlow = prefs.settings
            .map { it.notificationRelayEnabled }
            .distinctUntilChanged()
        scope.launch {
            combine(connectionState, relayEnabledFlow) { state, enabled ->
                relayEnabled = enabled
                state == ConnectionState.CONNECTED && enabled
            }.collectLatest { shouldSync ->
                if (!shouldSync) return@collectLatest
                // Sync promptly on connect (or when the toggle flips on while
                // connected). The link just came up, so the first write can drop on
                // momentary congestion — retry a few times with short backoff so the
                // board's ~2018 clock is corrected in seconds, not after a full period.
                var attempt = 0
                while (!sendTimeSync() && attempt < ON_CONNECT_RETRIES) {
                    attempt++
                    delay(ON_CONNECT_RETRY_DELAY_MS)
                }
                // Then keep drift out on the periodic cadence. collectLatest cancels
                // this loop the moment the link drops or mirroring turns off.
                while (true) {
                    delay(RESYNC_INTERVAL_MS)
                    sendTimeSync()
                }
            }
        }
        registerClockChangeReceiver()
    }

    /** Send one TIME_SYNC frame; returns true if the board acknowledged the write. */
    private suspend fun sendTimeSync(): Boolean {
        val epoch = nowMillis() / 1000L
        val tzMin = tzOffsetMinutes()
        val ok = sender.send(TimeSyncCommand.build(epochSeconds = epoch, tzOffsetMinutes = tzMin))
        Timber.tag(TAG).d("TIME_SYNC epoch=%d tz_offset_min=%d -> %s", epoch, tzMin, if (ok) "sent" else "dropped")
        return ok
    }

    /**
     * Resend on a phone timezone/DST/manual time change so the board's local-time
     * display tracks it without waiting for the next periodic tick. App-lived — the
     * dynamically-registered receiver stays for the process lifetime.
     */
    private fun registerClockChangeReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (!relayEnabled || connectionState.value != ConnectionState.CONNECTED) return
                scope.launch { sendTimeSync() }
            }
        }
        // ACTION_TIMEZONE_CHANGED/ACTION_TIME_CHANGED are protected system broadcasts,
        // so NOT_EXPORTED is correct and satisfies the Android 14+ (targetSdk 34+) flag
        // requirement explicitly instead of relying on the protected-broadcast exemption.
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
            .onSuccess { receiverRegistered = true }
            .onFailure { Timber.tag(TAG).w(it, "time-change receiver register failed") }
    }

    private companion object {
        const val TAG = "TimeSync"

        /** ~5 min keeps drift negligible; the board has no other time source. */
        const val RESYNC_INTERVAL_MS = 5 * 60 * 1000L

        /** Bounded retries for the on-connect sync if the first write drops. */
        const val ON_CONNECT_RETRIES = 3
        const val ON_CONNECT_RETRY_DELAY_MS = 2_000L
    }
}
