package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `flags` byte the board reads off a `PhoneNotification` frame
 * (docs/NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md §6).
 *
 * [PhoneNotificationSchema.FLAG_VOIP] means **display-only** — the board hides its
 * Answer button. It must be set for a VoIP call the app cannot answer (no Answer
 * PendingIntent, e.g. WhatsApp) and left clear for a call that IS answerable: a
 * native cellular call (TelecomManager) or a VoIP call that exposes an Answer
 * action (Telegram) — verified answerable end-to-end in the on-device relay log.
 *
 * Exercises the pure [NotificationClassifier.callFlags] seam so the wire-contract
 * rules are covered without an Android `StatusBarNotification`.
 */
class NotificationClassifierFlagsTest {

    private fun assertHas(flags: Int, bit: Int) =
        assertEquals("expected bit 0x${bit.toString(16)} set in 0x${flags.toString(16)}", bit, flags and bit)

    private fun assertLacks(flags: Int, bit: Int) =
        assertEquals("expected bit 0x${bit.toString(16)} clear in 0x${flags.toString(16)}", 0, flags and bit)

    @Test
    fun `unanswerable whatsapp call is flagged VoIP display-only`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = false, pkg = "com.whatsapp", hasAnswerAction = false
        )
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `unanswerable whatsapp business call is flagged VoIP display-only`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = true, pkg = "com.whatsapp.w4b", hasAnswerAction = false
        )
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `answerable telegram call is NOT flagged VoIP so board keeps Answer`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = false, pkg = "org.telegram.messenger", hasAnswerAction = true
        )
        assertLacks(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `answerable whatsapp call is NOT flagged VoIP`() {
        // Some WhatsApp call notifications do expose an Answer action; when they do,
        // the call is answerable via that intent and must stay unflagged.
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = false, pkg = "com.whatsapp", hasAnswerAction = true
        )
        assertLacks(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `native dialer call is never flagged VoIP`() {
        // Native cellular calls are answerable via TelecomManager regardless of whether
        // the posted notification carries an Answer action.
        val withAction = NotificationClassifier.callFlags(
            isCall = true, isOngoing = true, pkg = "com.android.server.telecom", hasAnswerAction = true
        )
        val withoutAction = NotificationClassifier.callFlags(
            isCall = true, isOngoing = true, pkg = "com.android.server.telecom", hasAnswerAction = false
        )
        assertLacks(withAction, PhoneNotificationSchema.FLAG_VOIP)
        assertLacks(withoutAction, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `ongoing call is flagged ongoing`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = true, pkg = "com.google.android.dialer", hasAnswerAction = true
        )
        assertHas(flags, PhoneNotificationSchema.FLAG_ONGOING)
    }

    @Test
    fun `ongoing unanswerable whatsapp call carries both ongoing and VoIP`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = true, pkg = "com.whatsapp", hasAnswerAction = false
        )
        assertHas(flags, PhoneNotificationSchema.FLAG_ONGOING)
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `ringing unanswerable whatsapp call is VoIP but not yet ongoing`() {
        val flags = NotificationClassifier.callFlags(
            isCall = true, isOngoing = false, pkg = "com.whatsapp", hasAnswerAction = false
        )
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
        assertLacks(flags, PhoneNotificationSchema.FLAG_ONGOING)
    }

    @Test
    fun `non-call messaging notification carries no flags`() {
        val flags = NotificationClassifier.callFlags(
            isCall = false, isOngoing = false, pkg = "com.whatsapp", hasAnswerAction = false
        )
        assertEquals(0, flags)
    }

    @Test
    fun `non-call notification never flagged even if ongoing`() {
        // A non-call ongoing notification is dropped by classify() anyway, but the
        // flag helper must never emit ONGOING/VOIP for a non-call frame.
        val flags = NotificationClassifier.callFlags(
            isCall = false, isOngoing = true, pkg = "org.telegram.messenger", hasAnswerAction = false
        )
        assertEquals(0, flags)
    }
}
