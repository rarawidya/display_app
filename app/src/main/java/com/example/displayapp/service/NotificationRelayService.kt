package com.example.displayapp.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.displayapp.DisplayApp
import com.example.displayapp.data.notification.NotificationClassifier
import com.example.displayapp.data.notification.PhoneNotificationSender
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
 * every posted/removed notification is classified ([NotificationClassifier]) and
 * pushed over BLE `0xAF07` as a framed Cap'n Proto `PhoneNotification`
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

    override fun onCreate() {
        super.onCreate()
        val container = (application as DisplayApp).appContainer
        sender = container.phoneNotificationSender
        prefs = container.appPreferencesRepository
        ownPackage = packageName
        scope.launch {
            for (event in events) {
                if (!relayEnabled()) continue
                val notification = when (event) {
                    is Event.Posted ->
                        NotificationClassifier.classify(event.sbn, appLabel(event.sbn.packageName))
                            ?: continue
                    is Event.Removed -> NotificationClassifier.dismissal(event.sbn)
                }
                sender.send(notification)
            }
        }
        Timber.tag(TAG).i("Notification listener created")
    }

    override fun onDestroy() {
        events.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == ownPackage) return // don't mirror our own notifications
        events.trySend(Event.Posted(n))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == ownPackage) return
        events.trySend(Event.Removed(n))
    }

    private suspend fun relayEnabled(): Boolean =
        runCatching { prefs.settings.first().notificationRelayEnabled }.getOrDefault(false)

    private fun appLabel(pkg: String?): String? {
        pkg ?: return null
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "NotifRelay"
    }
}
