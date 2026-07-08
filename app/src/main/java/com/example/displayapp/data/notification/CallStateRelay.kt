package com.example.displayapp.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.displayapp.data.protocol.PhoneNotificationSchema
import com.example.displayapp.data.protocol.PhoneNotificationSchema.PhoneNotification
import com.example.displayapp.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Mirrors the phone's **call state** to the board's banner.
 *
 * Incoming/ongoing calls do NOT reliably arrive through
 * [android.service.notification.NotificationListenerService] (the dialer's own
 * notification timing is OEM-dependent), so per docs/NOTIFICATION-APP-FIXME.md §3
 * calls get their own capture path: a [TelephonyCallback] (API 31+) or
 * [PhoneStateListener] (older), gated on `READ_PHONE_STATE`.
 *
 * Frames follow the board contract (APP-NOTIFICATION-INTEGRATION.md §5c):
 * - RINGING  → `category=1`, `flags.ongoing` — persistent "Incoming call" banner
 * - OFFHOOK  → `category=1`, `flags.ongoing` — persistent "In call" banner
 * - IDLE     → transient `category=1` "Call ended" frame with the **same id**;
 *   latest-wins replaces the persistent banner and it auto-expires (~12 s).
 *   (`flags.removed=1` is filtered board-side, so a dismissal frame can't be used.)
 *
 * Registration follows the user's relay toggle
 * ([com.example.displayapp.domain.model.AppSettings.notificationRelayEnabled]) —
 * [start] observes the settings flow and (un)registers on each change, so a
 * permission granted after the fact takes effect as soon as the toggle flips.
 * App-scoped: owned by the container, started once from
 * [com.example.displayapp.DisplayApp.onCreate].
 */
class CallStateRelay(
    private val context: Context,
    private val sender: PhoneNotificationSender,
    private val prefs: AppPreferencesRepository,
    private val scope: CoroutineScope
) {

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var registered = false
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    // Kept as fields so unregister() can hand the exact same instance back.
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    /** Begin following the relay toggle; idempotent per-emission (un)register. */
    fun start() {
        scope.launch {
            prefs.settings.map { it.notificationRelayEnabled }.distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) register() else unregister()
                }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun register() {
        val tm = telephony ?: return
        if (registered) return
        if (!hasPermission()) {
            Timber.tag(TAG).w("relay enabled but READ_PHONE_STATE not granted — calls won't mirror")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                telephonyCallback = cb
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleState(state)
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            registered = true
            Timber.tag(TAG).i("call-state relay registered")
        }.onFailure { Timber.tag(TAG).w(it, "call-state register failed") }
    }

    private fun unregister() {
        val tm = telephony ?: return
        if (!registered) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        phoneStateListener = null
        registered = false
        lastState = TelephonyManager.CALL_STATE_IDLE
        Timber.tag(TAG).i("call-state relay unregistered")
    }

    private fun handleState(state: Int) {
        Timber.tag(TAG).i("call state %d -> %d", lastState, state)
        val previous = lastState
        lastState = state
        val frame = when (state) {
            TelephonyManager.CALL_STATE_RINGING ->
                callFrame(body = "Incoming call", ongoing = true)
            TelephonyManager.CALL_STATE_OFFHOOK ->
                callFrame(body = "In call", ongoing = true)
            TelephonyManager.CALL_STATE_IDLE ->
                // Collapse the persistent banner only if we actually showed one.
                if (previous != TelephonyManager.CALL_STATE_IDLE) {
                    callFrame(body = "Call ended", ongoing = false)
                } else null
            else -> null
        } ?: return
        scope.launch { sender.send(frame) }
    }

    private fun callFrame(body: String, ongoing: Boolean) = PhoneNotification(
        id = CALL_BANNER_ID,
        timestampUnix = (System.currentTimeMillis() / 1000L).toInt(),
        category = PhoneNotificationSchema.CATEGORY_CALL,
        flags = if (ongoing) PhoneNotificationSchema.FLAG_ONGOING else 0,
        appName = "Phone",
        title = "Phone",
        body = body
    )

    private companion object {
        const val TAG = "NotifRelay"

        /** One stable id for the telephony banner so every state update replaces it. */
        const val CALL_BANNER_ID = 0x0CA11
    }
}
