package com.example.displayapp.data.notification

import com.example.displayapp.data.protocol.FrameEncoder
import com.example.displayapp.data.protocol.PhoneNotificationSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi STA credential commands (docs/BOARD-WIFI-STA-INTEGRATION.md §2) ride the
 * frozen control contract — category 32, appName "EVD", command in title, argument
 * in body — and every frame must fit one acknowledged GATT write.
 */
class BoardWifiCommandsTest {

    @Test
    fun `join sequence is SSID then PSK then JOIN on the control contract`() {
        val seq = BoardWifiCommands.joinSequence("Rara's Phone", "ride-safe-2026")

        assertEquals(3, seq.size)
        assertEquals(
            listOf(BoardWifiCommands.CMD_SSID, BoardWifiCommands.CMD_PSK, BoardWifiCommands.CMD_JOIN),
            seq.map { it.title },
        )
        assertEquals(listOf("Rara's Phone", "ride-safe-2026", ""), seq.map { it.body })
        seq.forEach {
            assertEquals(PhoneNotificationSchema.CATEGORY_CONTROL, it.category)
            assertEquals("EVD", it.appName)
            assertEquals(0, it.id)
        }
    }

    @Test
    fun `worst-case WPA2 credentials still fit one GATT write`() {
        // Max SSID (32 B) + max passphrase (63 chars) — each command frame ≤ 244 B.
        val seq = BoardWifiCommands.joinSequence("S".repeat(32), "p".repeat(63))
        seq.forEach {
            val frame = FrameEncoder.encode(PhoneNotificationSchema.encode(it))
            assertTrue("frame ${it.title} is ${frame.size} B", frame.size <= 244)
        }
        // The passphrase must survive encoding untruncated (body cap is 96 B ≥ 63).
        val psk = seq[1]
        assertEquals(63, psk.body.length)
    }

    @Test
    fun `forget is a bare control command`() {
        val f = BoardWifiCommands.forget()
        assertEquals(BoardWifiCommands.CMD_FORGET, f.title)
        assertEquals("", f.body)
        assertEquals(PhoneNotificationSchema.CATEGORY_CONTROL, f.category)
    }
}
