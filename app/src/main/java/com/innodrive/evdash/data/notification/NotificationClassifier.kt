package com.innodrive.evdash.data.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema.PhoneNotification

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
        val title = extractTitle(n)
        val body = extractBody(n)

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

        // Can this call actually be answered from the display? Native cellular calls
        // go through TelecomManager; a VoIP call can only be answered by firing its
        // notification's own Answer PendingIntent, so it's answerable iff that action
        // exists (Telegram exposes one; WhatsApp typically does not).
        val hasAnswerAction = isCall && CallNotificationActions.extract(n, pkg)?.answer != null
        val flags = callFlags(isCall, isOngoing, pkg, hasAnswerAction)

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
     * The `flags` byte for a classified banner. Pure so the wire-contract rules are
     * unit-testable without an Android [StatusBarNotification]:
     * - [PhoneNotificationSchema.FLAG_ONGOING] for a persistent (active/incoming) call
     *   banner the board must not auto-expire;
     * - [PhoneNotificationSchema.FLAG_VOIP] marks a **display-only** call the board
     *   can't answer, so it hides the Answer button (docs
     *   NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md §6). Set it only for a **VoIP** call
     *   (from a messaging app) that exposes **no** Answer action ([hasAnswerAction]
     *   false) — e.g. WhatsApp, which Android can't accept programmatically. A VoIP
     *   call that DOES expose an Answer action (Telegram) stays answerable via its own
     *   notification intent, so it is left unflagged and the board keeps Answer.
     *   Native cellular calls arrive via `CallStateRelay`/telephony or a dialer package
     *   and are answerable through `TelecomManager`, so they are never flagged.
     *
     * Non-call notifications carry no flags.
     */
    internal fun callFlags(
        isCall: Boolean,
        isOngoing: Boolean,
        pkg: String,
        hasAnswerAction: Boolean
    ): Int {
        if (!isCall) return 0
        var flags = 0
        if (isOngoing) flags = flags or PhoneNotificationSchema.FLAG_ONGOING
        val isVoip = pkg in WHATSAPP || pkg in TELEGRAM
        if (isVoip && !hasAnswerAction) flags = flags or PhoneNotificationSchema.FLAG_VOIP
        return flags
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
     * True when [sbn] is a phone call — by notification category, a known dialer
     * package, or a VoIP incoming-call ring from an allow-listed messaging app.
     * Shared with the call-action capture path so "is this a call?" is decided in
     * exactly one place.
     *
     * WhatsApp tags its call notification with [Notification.CATEGORY_CALL] (via
     * `CallStyle`), so the category branch catches it. **Telegram does not set
     * `CATEGORY_CALL`** on its incoming-call notification (classic full-screen
     * intent + `addAction` "Answer"/"Decline") and isn't a dialer package, so it
     * used to fall through to the messaging branch — its Answer/Hang-up intents
     * were never captured and a board Answer press silently no-op'd on the
     * `TelecomManager` fallback (docs/CALL-ACTION-INTEGRATION.md). A **full-screen
     * intent** is the reliable signal of an incoming call and is set by no other
     * notification type; gating on the allow-listed VoIP apps keeps a stray
     * full-screen notification elsewhere from being miscounted as a call.
     */
    fun isCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_CALL) return true
        val pkg = sbn.packageName.orEmpty()
        if (pkg in DIALER) return true
        if (pkg !in WHATSAPP && pkg !in TELEGRAM) return false
        // Incoming ring → full-screen intent; answered/active → ongoing + Hang up.
        // Recognizing the active state keeps the current hang-up intent captured so
        // a board End press works, not the stale ring-phase Decline (no-op when
        // connected) — the "End doesn't work" bug (docs/CALL-ACTION-INTEGRATION.md).
        if (n.fullScreenIntent != null) return true
        val ongoing = sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0
        return ongoing && CallNotificationActions.hasHangUp(n)
    }

    /**
     * Stable per-alert id from the notification key so an update/dismissal of the
     * same alert reuses it (§4 correlation). Falls back to the numeric id pre-key.
     */
    fun stableId(sbn: StatusBarNotification): Int = sbn.key?.hashCode() ?: sbn.id

    /**
     * Resolve the banner title, tolerating notification styles that leave
     * [Notification.EXTRA_TITLE] blank. WhatsApp/Telegram/modern SMS apps use
     * `MessagingStyle`, whose sender/conversation lives in
     * [Notification.EXTRA_CONVERSATION_TITLE]; without this fallback a real message
     * can arrive with an empty title+body and get dropped as "content-less" (the
     * empty-content filter in [classify]) even though the listener fired.
     */
    private fun extractTitle(n: Notification): String {
        val extras = n.extras ?: return ""
        return firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        )
    }

    /**
     * Resolve the banner body across the common notification styles, in order of
     * how specific they are: plain text → BigText → the last InboxStyle line → the
     * latest MessagingStyle message → sub-text. Guards the "listener fires but the
     * frame is empty → dropped" path for apps that don't populate
     * [Notification.EXTRA_TEXT] (many MessagingStyle implementations).
     */
    private fun extractBody(n: Notification): String {
        val extras = n.extras ?: return ""
        val direct = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
        if (direct.isNotBlank()) return direct

        // InboxStyle: use the most recent line.
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.lastOrNull { !it.isNullOrBlank() }
            ?.let { return it.toString() }

        // MessagingStyle: the latest message's text (androidx parses the parcelables).
        runCatching {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n)
                ?.messages
                ?.lastOrNull { !it.text.isNullOrBlank() }
                ?.text
        }.getOrNull()?.let { return it.toString() }

        return extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
    }

    private fun firstNonBlank(vararg candidates: CharSequence?): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
}
