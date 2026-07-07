package com.example.displayapp.data.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.example.displayapp.data.protocol.PhoneNotificationSchema
import com.example.displayapp.data.protocol.PhoneNotificationSchema.PhoneNotification

/**
 * Maps an Android [StatusBarNotification] to the board's [PhoneNotification]
 * contract (docs/APP-NOTIFICATION-INTEGRATION.md §3): **category first, then the
 * posting package** decides the branded messaging apps (WhatsApp / Telegram). The
 * board renders the right glyph from `category` + `appName`.
 *
 * Canonical strings match §3's table (WhatsApp / Telegram / Messages / Phone).
 * Anything unrecognized still relays as a generic banner (`category = 0`) rather
 * than being dropped — the board shows it with a message glyph.
 */
object NotificationClassifier {

    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val TELEGRAM = setOf("org.telegram.messenger", "org.telegram.plus", "org.telegram.messenger.web")
    private val SMS = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms"
    )
    private val DIALER = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.android.incallui"
    )

    /**
     * Classify a posted notification.
     *
     * @param appLabel human-readable label of the posting app, used for the
     *   generic ([PhoneNotificationSchema.CATEGORY_OTHER]) path.
     * @return a ready-to-send notification, or null when it should be skipped
     *   (group-summary shell, or a content-less non-call notification).
     */
    fun classify(sbn: StatusBarNotification, appLabel: String?): PhoneNotification? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val pkg = sbn.packageName.orEmpty()
        val isCall = n.category == Notification.CATEGORY_CALL || pkg in DIALER

        if (!isCall) {
            // Group summaries duplicate their children; skip the shell.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            // Nothing to show on the banner.
            if (title.isBlank() && body.isBlank()) return null
        }

        val (category, appName) = when {
            isCall          -> PhoneNotificationSchema.CATEGORY_CALL to "Phone"
            pkg in WHATSAPP -> PhoneNotificationSchema.CATEGORY_MESSAGING_APP to "WhatsApp"
            pkg in TELEGRAM -> PhoneNotificationSchema.CATEGORY_MESSAGING_APP to "Telegram"
            pkg in SMS      -> PhoneNotificationSchema.CATEGORY_MESSAGE_SMS to "Messages"
            else            -> PhoneNotificationSchema.CATEGORY_OTHER to (appLabel ?: pkg)
        }

        var flags = 0
        if (sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            flags = flags or PhoneNotificationSchema.FLAG_ONGOING
        }

        return PhoneNotification(
            id = stableId(sbn),
            timestampUnix = (sbn.postTime / 1000L).toInt(),
            category = category,
            flags = flags,
            appName = appName,
            title = title,
            body = body
        )
    }

    /**
     * A removal → a dismiss frame with the **same id** and `flags.removed` so the
     * board collapses the banner it's currently showing for this alert (§4).
     */
    fun dismissal(sbn: StatusBarNotification): PhoneNotification =
        PhoneNotification(
            id = stableId(sbn),
            timestampUnix = (sbn.postTime / 1000L).toInt(),
            category = PhoneNotificationSchema.CATEGORY_OTHER,
            flags = PhoneNotificationSchema.FLAG_REMOVED
        )

    /**
     * Stable per-alert id from the notification key so an update/dismissal of the
     * same alert reuses it (§4 correlation). Falls back to the numeric id pre-key.
     */
    private fun stableId(sbn: StatusBarNotification): Int = sbn.key?.hashCode() ?: sbn.id
}
