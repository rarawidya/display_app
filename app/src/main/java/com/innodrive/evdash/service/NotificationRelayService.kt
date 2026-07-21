package com.innodrive.evdash.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.innodrive.evdash.DisplayApp
import com.innodrive.evdash.data.notification.ActiveCallActions
import com.innodrive.evdash.data.notification.CallNotificationActions
import com.innodrive.evdash.data.notification.NotificationClassifier
import com.innodrive.evdash.data.notification.PhoneNotificationSender
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridges the phone's status-bar notifications to the board's on-screen banner.
 *
 * Once the user (1) grants **Notification access** in system settings — a
 * system-bound grant, not a runtime permission — and (2) enables the in-app
 * toggle ([com.innodrive.evdash.domain.model.AppSettings.notificationRelayEnabled]),
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

    // Cached "Mirror to vehicle display" toggle. Reading `prefs.settings.first()` per
    // event hit the disk on every notification and, on any transient DataStore read
    // error, fell back to `false` and silently dropped that one event. A single
    // resilient collector keeps the last known value, so an occasional read failure
    // no longer costs a message.
    @Volatile private var relayEnabledFlag = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as DisplayApp).appContainer
        sender = container.phoneNotificationSender
        prefs = container.appPreferencesRepository
        ownPackage = packageName
        scope.launch {
            prefs.settings
                .map { it.notificationRelayEnabled }
                .catch { Timber.tag(TAG).w(it, "relay-toggle read failed — keeping last known") }
                .collect { relayEnabledFlag = it }
        }
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
        isConnected = true
        Timber.tag(TAG).i("onListenerConnected — system bound the notification listener")
    }

    override fun onListenerDisconnected() {
        isConnected = false
        Timber.tag(TAG).w("onListenerDisconnected — listener unbound; requesting rebind")
        // Self-heal: aggressive OEMs (Xiaomi/MIUI) and Doze silently unbind the
        // listener mid-session, after which onNotificationPosted never fires again and
        // nothing recovers it until the app is cold-started. requestRebind is the
        // framework's documented recovery for exactly this callback — a no-op if
        // access was actually revoked. Without it, "worked earlier, dead now" is the
        // rule on MIUI (docs/NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md).
        requestRebindIfGranted(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        isConnected = false
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
     * notification so a board button press ([com.innodrive.evdash.data.notification.CallControlHandler])
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

    private fun relayEnabled(): Boolean = relayEnabledFlag

    companion object {
        private const val TAG = "NotifRelay"

        /**
         * True while the system has this listener bound (between
         * [onListenerConnected] and [onListenerDisconnected]/[onDestroy]). Read by
         * the UI to decide whether the lightweight [requestRebindIfGranted] is
         * enough or the heavier [forceRebind] is warranted, and to surface a
         * "granted but not connected" state to the user.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

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

        /**
         * Force the framework to rebind the listener by cycling the component's
         * enabled state, then asking for a rebind.
         *
         * [requestRebind] alone is unreliable after an app **update** — the OS can
         * leave the listener permanently unbound until a reboot or a manual access
         * off/on, so real notifications never reach [onNotificationPosted] even
         * though access is still granted (the exact "telephony call mirrors but
         * WhatsApp/Telegram messages don't" symptom the firmware audit isolated to
         * a silent listener path — docs/NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md).
         * Toggling the component `DISABLED → ENABLED` with `DONT_KILL_APP` makes
         * `NotificationManagerService` re-evaluate its listeners and rebind ours;
         * the grant (stored as the enabled-listeners setting string) survives the
         * cycle. The re-enable is issued synchronously in the same call so the
         * component is never left disabled.
         *
         * Only meaningful when access is granted; a no-op otherwise. Call this from
         * the "granted but [isConnected] == false" path so a healthy binding is
         * never churned.
         */
        fun forceRebind(context: Context) {
            val component = ComponentName(context, NotificationRelayService::class.java)
            val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
            if (!granted) return
            runCatching {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                requestRebind(component)
                Timber.tag(TAG).i("forceRebind — cycled component enabled state to rebind listener")
            }.onFailure { Timber.tag(TAG).w(it, "forceRebind failed") }
        }
    }
}
