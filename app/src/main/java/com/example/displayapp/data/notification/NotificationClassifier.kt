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
 * **Only calls, SMS, WhatsApp, and Telegram are relayed** — anything else returns
 * null and never reaches the board (product decision: the cluster shows the four
 * categories the rider cares about; everything else is noise at riding speed).
 *
 * Board-side filter rules (docs/NOTIFICATION-APP-FIXME.md §4) shape every frame:
 * the UI drops `category = 0`, `flags.removed = 1`, and empty-content frames, so
 * - `title`/`body` are backfilled so both are always non-empty;
 * - `flags.ongoing` is set for **calls only** — the board never auto-expires an
 *   ongoing banner, so a mirrored media/download notification would pin the
 *   cluster forever. Non-call ongoing notifications are skipped outright;
 * - a call that ends is collapsed with a *transient* [callEnded] frame (same id,
 *   latest-wins replaces the persistent banner, auto-expires ~12 s) instead of a
 *   filtered `removed = 1` dismissal.
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
     * @return a ready-to-send notification, or **null when it should not relay**:
     *   any app outside the allow-list (calls / SMS / WhatsApp / Telegram),
     *   group-summary shells, content-less non-call notifications, and non-call
     *   ongoing notifications such as media players.
     */
    fun classify(sbn: StatusBarNotification): PhoneNotification? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val pkg = sbn.packageName.orEmpty()
        val isCall = isCall(sbn)
        val isOngoing = sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0

        if (!isCall) {
            // Group summaries duplicate their children; skip the shell.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            // Nothing to show on the banner.
            if (title.isBlank() && body.isBlank()) return null
            // Ongoing = non-dismissable (media playback, downloads, nav apps…).
            // The board never expires an ongoing banner — don't pin the cluster.
            if (isOngoing) return null
        }

        val (category, appName) = when {
            isCall          -> PhoneNotificationSchema.CATEGORY_CALL to "Phone"
            pkg in WHATSAPP -> PhoneNotificationSchema.CATEGORY_MESSAGING_APP to "WhatsApp"
            pkg in TELEGRAM -> PhoneNotificationSchema.CATEGORY_MESSAGING_APP to "Telegram"
            pkg in SMS      -> PhoneNotificationSchema.CATEGORY_MESSAGE_SMS to "Messages"
            // Allow-list only: everything else stays on the phone.
            else            -> return null
        }

        var flags = 0
        if (isCall && isOngoing) {
            flags = flags or PhoneNotificationSchema.FLAG_ONGOING
        }

        // The board filters empty-content frames — guarantee both lines.
        val safeTitle = title.ifBlank { if (isCall) "Call" else appName }
        val safeBody = body.ifBlank { if (isCall) "Incoming call" else safeTitle }

        return PhoneNotification(
            id = stableId(sbn),
            timestampUnix = (sbn.postTime / 1000L).toInt(),
            category = category,
            flags = flags,
            appName = appName,
            title = safeTitle,
            body = safeBody
        )
    }

    /**
     * Collapse a persistent (ongoing) call banner after the call notification is
     * removed. `flags.removed = 1` is filtered by the board UI, so instead send a
     * **transient** frame with the same id: latest-wins replaces the ongoing
     * banner and the ~12 s auto-expiry clears the screen.
     */
    fun callEnded(sbn: StatusBarNotification): PhoneNotification =
        PhoneNotification(
            id = stableId(sbn),
            timestampUnix = (sbn.postTime / 1000L).toInt(),
            category = PhoneNotificationSchema.CATEGORY_CALL,
            flags = 0,
            appName = "Phone",
            title = "Phone",
            body = "Call ended"
        )

    /**
     * True when [sbn] is a phone call — by notification category or a known dialer
     * package. Shared with the call-action capture path so "is this a call?" is
     * decided in exactly one place.
     */
    fun isCall(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification?.category
        return category == Notification.CATEGORY_CALL || sbn.packageName.orEmpty() in DIALER
    }

    /**
     * Stable per-alert id from the notification key so an update/dismissal of the
     * same alert reuses it (§4 correlation). Falls back to the numeric id pre-key.
     */
    fun stableId(sbn: StatusBarNotification): Int = sbn.key?.hashCode() ?: sbn.id
}
