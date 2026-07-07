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

    override fun onCreate() {
        super.onCreate()
        val container = (application as DisplayApp).appContainer
        sender = container.phoneNotificationSender
        prefs = container.appPreferencesRepository
        ownPackage = packageName
        Timber.tag(TAG).i("Notification listener created")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == ownPackage) return // don't mirror our own notifications
        scope.launch {
            if (!relayEnabled()) return@launch
            val notification = NotificationClassifier.classify(n, appLabel(n.packageName))
                ?: return@launch
            sender.send(notification)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == ownPackage) return
        scope.launch {
            if (!relayEnabled()) return@launch
            sender.send(NotificationClassifier.dismissal(n))
        }
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
