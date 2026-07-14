package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.data.protocol.PhoneNotificationSchema.PhoneNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encoder self-test mandated by docs/APP-NOTIFICATION-INTEGRATION.md §5a: the
 * message/SMS golden frame is byte-exact against the board's own decoder. If this
 * passes, the Cap'n Proto + framing pipeline is wire-correct; 5b–5d then only vary
 * field values.
 */
class PhoneNotificationFrameTest {

    @Test
    fun `encodes the spec §5a golden message frame byte-exact`() {
        val n = PhoneNotification(
            id = 4242,
            timestampUnix = 1751800000,
            category = 2,
            flags = 0,
            appName = "Messages",
            title = "Alice",
            body = "On my way"
        )

        val frame = FrameEncoder.encode(PhoneNotificationSchema.encode(n))

        val golden = hex(
            """
            AA 60 00 00 00 00 0B 00 00 00 00 00 00 00 02 00 03 00 92 10 00 00 C0 58 6A 68
            02 00 00 00 00 00 00 00 09 00 00 00 4A 00 00 00 0D 00 00 00 32 00 00 00 0D 00
            00 00 52 00 00 00 4D 65 73 73 61 67 65 73 00 00 00 00 00 00 00 00 41 6C 69 63
            65 00 00 00 4F 6E 20 6D 79 20 77 61 79 00 00 00 00 00 00 00 E1 1C
            """
        )

        assertEquals(golden.toHex(), frame.toHex())
    }

    @Test
    fun `empty texts are encoded as null pointers`() {
        // §5d clear-all: empty appName/title/body → 2 data words + 3 null pointer
        // words, no text region. Payload = 8 (seg table) + 8 (root) + 16 + 24 = 56.
        val payload = PhoneNotificationSchema.encode(
            PhoneNotification(id = 0, category = PhoneNotificationSchema.CATEGORY_CLEAR_ALL)
        )
        assertEquals(56, payload.size)
        // The three pointer words (bytes 32..55) are all zero (null pointers).
        for (i in 32 until 56) assertEquals("byte $i", 0, payload[i].toInt())
    }

    @Test
    fun `long body is truncated to the 96-byte cap`() {
        val payload = PhoneNotificationSchema.encode(
            PhoneNotification(id = 1, category = 3, appName = "WhatsApp", body = "x".repeat(500))
        )
        // Whole frame must stay within the board's single-write budget (§1, ≤ 244 B).
        assertTrue("frame within MTU budget", FrameEncoder.encode(payload).size <= 244)
    }

    @Test
    fun `all three fields maxed stays within the board's no-reassembly limit`() {
        // Worst case: a generic app (long label) with a maxed title AND body. Before
        // the body budget was added this encoded to a 248 B payload / 252 B frame —
        // 8 B over the board's hard ceiling — and the board (no RX reassembly) dropped
        // it, so the notification never showed. Every frame must now fit one ATT write.
        val payload = PhoneNotificationSchema.encode(
            PhoneNotification(
                id = 7,
                category = PhoneNotificationSchema.CATEGORY_OTHER,
                appName = "A".repeat(200),
                title = "B".repeat(200),
                body = "C".repeat(200)
            )
        )
        assertTrue(
            "payload ${payload.size} B must be ≤ ${PhoneNotificationSchema.MAX_FRAME_PAYLOAD_BYTES}",
            payload.size <= PhoneNotificationSchema.MAX_FRAME_PAYLOAD_BYTES
        )
        assertTrue("frame within single-write budget", FrameEncoder.encode(payload).size <= 244)
    }

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
