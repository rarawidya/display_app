package com.example.displayapp.data.notification

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.CallControlSchema
import com.example.displayapp.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Acts on the cluster's call buttons: decodes board→phone `CallControl` events
 * from the `0xAF05` uplink ([BluetoothDataSource.controlFrames],
 * docs/CALL-CONTROL-INTEGRATION.md) and answers/ends the phone call via
 * [TelecomManager].
 *
 * Gates, in order: frame validity (CRC etc. — [CallControlSchema.decode]),
 * retransmit dedup (`seq` repeat of the last event), the user's *Mirror to
 * vehicle display* toggle, then the `ANSWER_PHONE_CALLS` runtime permission.
 * Everything that doesn't act is logged and dropped — there is no error reply
 * in the v1 contract; the board's implicit ACK is the banner update that
 * `CallStateRelay` pushes when the call state actually changes.
 *
 * Acting on the call is a **two-tier** strategy (docs/CALL-ACTION-INTEGRATION.md,
 * final status 2026-07-08 — the BLE chain is proven; TelecomManager alone couldn't
 * answer the WhatsApp VoIP calls under test):
 *  1. **Fire the call notification's own Answer/Hang-up action** ([ActiveCallActions],
 *     captured by the notification listener). This is the ONLY path that reaches a
 *     third-party VoIP call (WhatsApp / Telegram `ConnectionService`).
 *  2. **Fall back to [TelecomManager]** (`acceptRingingCall` API 26+, `endCall`
 *     API 28+) for a plain cellular call, whose notification may carry no actions.
 *
 * Both TelecomManager calls are deprecated (Google steers companion devices to
 * CDM + InCallService, which requires being the default dialer — inappropriate for
 * a cockpit app), so the notification-action path is preferred and this stays the
 * one class that changes if the strategy evolves.
 */
class CallControlHandler(
    private val context: Context,
    private val dataSource: BluetoothDataSource,
    private val prefs: AppPreferencesRepository,
    private val scope: CoroutineScope,
) {

    private var lastSeq = -1

    /** Start consuming control frames; call once from app startup. */
    fun start() {
        scope.launch {
            dataSource.controlFrames.collect { frame -> onFrame(frame) }
        }
    }

    private suspend fun onFrame(frame: ByteArray) {
        val event = CallControlSchema.decode(frame)
        if (event == null) {
            Timber.tag(TAG).w("call-control: bad frame (%d B) — dropped", frame.size)
            return
        }
        if (event.seq == lastSeq) {
            Timber.tag(TAG).d("call-control: duplicate seq=%d — retransmit dropped", event.seq)
            return
        }
        lastSeq = event.seq

        if (!relayEnabled()) {
            Timber.tag(TAG).i("call-control: relay toggle off — action=%d ignored", event.action)
            return
        }
        if (!hasPermission()) {
            Timber.tag(TAG).w("call-control: ANSWER_PHONE_CALLS not granted — action=%d ignored", event.action)
            return
        }

        when (event.action) {
            CallControlSchema.ACTION_ANSWER -> answer(event)
            CallControlSchema.ACTION_HANG_UP -> hangUp(event)
            // Board-local banner dismiss; informational only in v1.
            CallControlSchema.ACTION_DISMISS ->
                Timber.tag(TAG).d("call-control: dismiss seq=%d (no phone action)", event.seq)
            else ->
                Timber.tag(TAG).w("call-control: unknown action=%d — ignored", event.action)
        }
    }

    private fun answer(event: CallControlSchema.CallControl) {
        // Tier 1: the ringing notification's own Answer action (reaches VoIP calls).
        if (fire(ActiveCallActions.current()?.answer)) {
            log(event, "answered (notification action)")
            return
        }
        // Tier 2: cellular fallback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timber.tag(TAG).w("call-control: answer needs API 26+ (no notification action) — ignored")
            return
        }
        val result = runCatching {
            @Suppress("DEPRECATION")
            telecom()?.acceptRingingCall()
        }
        log(event, if (result.isSuccess) "answered (telecom)" else "failed: ${result.exceptionOrNull()}")
    }

    private fun hangUp(event: CallControlSchema.CallControl) {
        // Tier 1: the call notification's own Decline/Hang-up action (reaches VoIP).
        if (fire(ActiveCallActions.current()?.hangUp)) {
            log(event, "ended (notification action)")
            return
        }
        // Tier 2: cellular fallback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Timber.tag(TAG).w("call-control: hangUp needs API 28+ (no notification action) — ignored")
            return
        }
        val result = runCatching {
            @Suppress("DEPRECATION")
            telecom()?.endCall()
        }
        log(event, if (result.isSuccess) "ended (telecom)" else "failed: ${result.exceptionOrNull()}")
    }

    /**
     * Send a captured call PendingIntent; true if it fired, false if absent/cancelled.
     *
     * WhatsApp/Telegram's **Answer** action launches an activity (their in-call UI),
     * and Android blocks a background service from starting an activity — so a plain
     * `send()` fires but the call never connects (the notification just keeps
     * re-ringing). We opt into a **background activity start** via [ActivityOptions]
     * so the accept activity is allowed to launch; harmless for the Decline/Hang-up
     * action, which is a broadcast/service and doesn't need it.
     */
    private fun fire(intent: PendingIntent?): Boolean {
        if (intent == null) return false
        val options = ActivityOptions.makeBasic().apply {
            when {
                Build.VERSION.SDK_INT >= 34 -> setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    @Suppress("DEPRECATION")
                    setPendingIntentBackgroundActivityLaunchAllowed(true)
                }
            }
        }
        return runCatching {
            intent.send(context, 0, null, null, null, null, options.toBundle())
        }.onFailure { Timber.tag(TAG).w(it, "call-control: notification action send failed") }
            .isSuccess
    }

    private fun log(event: CallControlSchema.CallControl, outcome: String) {
        Timber.tag(TAG).i("call-control: action=%d seq=%d → %s", event.action, event.seq, outcome)
    }

    private fun telecom(): TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun relayEnabled(): Boolean =
        runCatching { prefs.settings.first().notificationRelayEnabled }.getOrDefault(false)

    private companion object {
        const val TAG = "NotifRelay"
    }
}
