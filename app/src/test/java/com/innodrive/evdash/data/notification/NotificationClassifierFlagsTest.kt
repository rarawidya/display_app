package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `flags` byte the board reads off a `PhoneNotification` frame
 * (docs/NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md §6). A third-party VoIP call
 * (WhatsApp/Telegram) is display-only — Android exposes no API to answer another
 * app's VoIP call — so it must carry [PhoneNotificationSchema.FLAG_VOIP] for the
 * board to hide the (non-functional) Answer button. Native cellular calls stay
 * answerable and must NOT be flagged VoIP.
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
    fun `whatsapp call is flagged VoIP`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = false, pkg = "com.whatsapp")
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `whatsapp business call is flagged VoIP`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = false, pkg = "com.whatsapp.w4b")
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `telegram call is flagged VoIP`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = false, pkg = "org.telegram.messenger")
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `native dialer call is not flagged VoIP`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = true, pkg = "com.google.android.dialer")
        assertLacks(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `ongoing call is flagged ongoing`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = true, pkg = "com.google.android.dialer")
        assertHas(flags, PhoneNotificationSchema.FLAG_ONGOING)
    }

    @Test
    fun `ongoing whatsapp call carries both ongoing and VoIP`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = true, pkg = "com.whatsapp")
        assertHas(flags, PhoneNotificationSchema.FLAG_ONGOING)
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
    }

    @Test
    fun `ringing whatsapp call is VoIP but not yet ongoing`() {
        val flags = NotificationClassifier.callFlags(isCall = true, isOngoing = false, pkg = "com.whatsapp")
        assertHas(flags, PhoneNotificationSchema.FLAG_VOIP)
        assertLacks(flags, PhoneNotificationSchema.FLAG_ONGOING)
    }

    @Test
    fun `non-call messaging notification carries no flags`() {
        val flags = NotificationClassifier.callFlags(isCall = false, isOngoing = false, pkg = "com.whatsapp")
        assertEquals(0, flags)
    }

    @Test
    fun `non-call notification never flagged even if ongoing`() {
        // A non-call ongoing notification is dropped by classify() anyway, but the
        // flag helper must never emit ONGOING/VOIP for a non-call frame.
        val flags = NotificationClassifier.callFlags(isCall = false, isOngoing = true, pkg = "org.telegram.messenger")
        assertEquals(0, flags)
    }
}
