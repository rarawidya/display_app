package com.example.displayapp.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.displayapp.DisplayApp
import com.example.displayapp.data.notification.ActiveCallActions
import com.example.displayapp.data.notification.CallNotificationActions
import com.example.displayapp.data.notification.NotificationClassifier
import com.example.displayapp.data.notification.PhoneNotificationSender
import com.example.displayapp.data.protocol.PhoneNotificationSchema
import com.example.displayapp.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridges the phone's status-bar notifications to the board's on-screen banner.
 *
 * Once the user (1) grants **Notification access** in system settings — a
 * system-bound grant, not a runtime permission — and (2) enables the in-app
 * toggle ([com.example.displayapp.domain.model.AppSettings.notificationRelayEnabled]),
 * posted notifications from the allow-listed apps (calls / SMS / WhatsApp /
 * Telegram — see [NotificationClassifier]; everything else never leaves the phone)
 * are pushed over BLE `0xAF07` as a framed Cap'n Proto `PhoneNotification`
 * (docs/APP-NOTIFICATION-INTEGRATION.md). The board renders it (latest-wins,
 * single banner) so the rider sees calls/messages without looking at the phone.
 *
 * The in-app toggle gates delivery so merely granting system access never starts
 * silently mirroring — the user opts in inside the app.
 */
class NotificationRelayService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sender: PhoneNotificationSender
    private lateinit var prefs: AppPreferencesRepository
    private var ownPackage: String = ""

    /** Sealed feed of listener callbacks. */
    private sealed interface Event {
        val sbn: StatusBarNotification
        data class Posted(override val sbn: StatusBarNotification) : Event
        data class Removed(override val sbn: StatusBarNotification) : Event
    }

    // A single ordered queue drained by one consumer. Independent launch{}-per-
    // callback let a dismissal overtake its own post (board is latest-wins by id,
    // so a stale post landing after the dismiss re-shows a cleared banner).
    private val events = Channel<Event>(Channel.UNLIMITED)

    // Ids we relayed with flags.ongoing set (persistent call banners). The board
    // never auto-expires those, and it filters `removed=1` frames — so on removal
    // we send a transient "Call ended" replacement for tracked ids only, and drop
    // every other removal (a removed frame would wrongly clear the latest-wins
    // banner even when it belongs to a different notification).
    private val ongoingIds = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        val container = (application as DisplayApp).appContainer
        sender = container.phoneNotificationSender
        prefs = container.appPreferencesRepository
        ownPackage = packageName
        scope.launch {
            // Some OEMs (observed on Samsung/OneUI) deliver the same listener
            // callback twice back-to-back; the frames come out byte-identical
            // (timestampUnix has 1 s resolution), so drop consecutive repeats
            // rather than writing the same banner to the board twice.
            var lastSent: PhoneNotificationSchema.PhoneNotification? = null
            for (event in events) {
                if (!relayEnabled()) {
                    Timber.tag(TAG).v("relay disabled — dropping %s", event.sbn.packageName)
                    continue
                }
                val notification = when (event) {
                    is Event.Posted -> {
                        val classified = NotificationClassifier.classify(event.sbn) ?: continue
                        val id = NotificationClassifier.stableId(event.sbn)
                        if (classified.flags and PhoneNotificationSchema.FLAG_ONGOING != 0) {
                            ongoingIds.add(id)
                        } else {
                            ongoingIds.remove(id)
                        }
                        classified
                    }
                    is Event.Removed -> {
                        // Only a tracked ongoing (call) banner needs collapsing;
                        // transient banners auto-expire on the board (~12 s).
                        if (!ongoingIds.remove(NotificationClassifier.stableId(event.sbn))) continue
                        NotificationClassifier.callEnded(event.sbn)
                    }
                }
                if (notification == lastSent) continue
                lastSent = notification
                sender.send(notification)
            }
        }
        Timber.tag(TAG).i("Notification listener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.tag(TAG).i("onListenerConnected — system bound the notification listener")
    }

    override fun onListenerDisconnected() {
        Timber.tag(TAG).w("onListenerDisconnected — listener unbound; no notifications will relay")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        events.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // First statement on purpose: `adb logcat | grep -i onNotificationPosted`
        // is the firmware team's split-the-bug-in-half probe (NOTIFICATION-APP-FIXME.md).
        Timber.tag(TAG).i("onNotificationPosted pkg=%s key=%s", sbn?.packageName, sbn?.key)
        val n = sbn ?: return
        if (n.packageName == ownPackage) return // don't mirror our own notifications
        captureCallActions(n)
        events.trySend(Event.Posted(n))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Timber.tag(TAG).i("onNotificationRemoved pkg=%s key=%s", sbn?.packageName, sbn?.key)
        val n = sbn ?: return
        if (n.packageName == ownPackage) return
        if (NotificationClassifier.isCall(n)) ActiveCallActions.clearIfSource(n.key)
        events.trySend(Event.Removed(n))
    }

    /**
     * Stash the fireable Answer/Hang-up PendingIntents of an incoming/ongoing call
     * notification so a board button press ([com.example.displayapp.data.notification.CallControlHandler])
     * can drive the originating app's own accept/decline flow — the only path that
     * works for third-party VoIP (WhatsApp/Telegram) calls, which TelecomManager
     * can't touch. Independent of the relay toggle: the intents stay on the phone
     * and are only used when the board actually asks to act on the call.
     */
    private fun captureCallActions(sbn: StatusBarNotification) {
        if (!NotificationClassifier.isCall(sbn)) return
        val n = sbn.notification ?: return
        CallNotificationActions.extract(n, sbn.packageName.orEmpty())?.let { actions ->
            ActiveCallActions.set(actions, sbn.key)
            Timber.tag(TAG).i(
                "call actions captured pkg=%s answer=%b hangUp=%b",
                sbn.packageName, actions.answer != null, actions.hangUp != null
            )
        }
    }

    private suspend fun relayEnabled(): Boolean =
        runCatching { prefs.settings.first().notificationRelayEnabled }.getOrDefault(false)

    companion object {
        private const val TAG = "NotifRelay"

        /**
         * Nudge the system to (re)bind this listener when the user has granted
         * "Notification access" but the service isn't currently connected.
         *
         * Android does **not** reliably bind a `NotificationListenerService` on its
         * own after an app **install/update** — the binding can stay dead until a
         * reboot or an access toggle off/on, during which nothing is relayed. Calling
         * [requestRebind] at cold start and right after the user returns from the
         * access-grant screen closes that gap. A no-op when access isn't granted (the
         * system rejects the request) or the service is already bound. Safe on API 24+
         * (our minSdk); wrapped in [runCatching] since it can throw on odd OEM builds.
         */
        fun requestRebindIfGranted(context: Context) {
            val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
            if (!granted) return
            runCatching {
                requestRebind(ComponentName(context, NotificationRelayService::class.java))
            }.onFailure { Timber.tag(TAG).w(it, "requestRebind failed") }
        }
    }
}
