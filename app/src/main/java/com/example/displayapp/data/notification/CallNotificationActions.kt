package com.example.displayapp.data.notification

import android.app.Notification
import android.app.PendingIntent

/**
 * The fireable **Answer** / **Hang-up** actions extracted from an incoming- or
 * ongoing-call notification. Firing these PendingIntents drives the *originating
 * app's own* accept/decline flow — which is the ONLY way to answer or end a
 * third-party **VoIP** call (WhatsApp / Telegram): `TelecomManager.acceptRingingCall()`
 * / `endCall()` reach only the default cellular phone account, not a
 * `ConnectionService` VoIP call. (docs/CALL-ACTION-INTEGRATION.md, final status
 * 2026-07-08: BLE chain proven, phone received frames but couldn't act on the
 * WhatsApp calls under test.)
 */
data class CallActions(
    val answer: PendingIntent?,
    val hangUp: PendingIntent?,
    val packageName: String,
)

object CallNotificationActions {

    // Action-title keywords, lowercased. Titles are localized, so match the English
    // + Indonesian (this device's locale) verbs plus common synonyms — the same
    // pragmatic signal wearable/companion apps use to fire call actions from a
    // notification they don't own.
    private val ANSWER = listOf("answer", "accept", "pick up", "jawab", "terima", "angkat")
    private val HANGUP = listOf(
        "decline", "reject", "hang up", "hangup", "end call", "end", "dismiss",
        "tolak", "tutup", "akhiri", "akhir",
    )

    /**
     * Pull the answer/hang-up actions out of a call [notification], or null if it
     * exposes no actionable buttons. Each [Notification.Action] is classified by its
     * title keyword; for a two-button incoming call where nothing matched "answer",
     * the remaining (non-hang-up) button is taken as the accept action.
     */
    fun extract(notification: Notification, packageName: String): CallActions? {
        val actions = notification.actions?.toList().orEmpty()
        if (actions.isEmpty()) return null

        var answer: PendingIntent? = null
        var hangUp: PendingIntent? = null
        for (a in actions) {
            val title = a.title?.toString()?.lowercase().orEmpty()
            when {
                HANGUP.any { title.contains(it) } -> if (hangUp == null) hangUp = a.actionIntent
                ANSWER.any { title.contains(it) } -> if (answer == null) answer = a.actionIntent
            }
        }
        // Two-button incoming call, hang-up found but no title matched "answer":
        // the other button is the accept action (covers unrecognised locales).
        if (answer == null && hangUp != null && actions.size == 2) {
            answer = actions.map { it.actionIntent }.firstOrNull { it != null && it != hangUp }
        }
        if (answer == null && hangUp == null) return null
        return CallActions(answer, hangUp, packageName)
    }
}
