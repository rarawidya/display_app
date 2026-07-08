package com.example.displayapp.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoder tests for the board→phone control uplink (docs/CALL-CONTROL-INTEGRATION.md).
 * The golden frame is the byte-exact reference published in the spec (§3) — the
 * firmware team's encoder self-test must produce exactly these bytes.
 */
class CallControlSchemaTest {

    // id=42, seq=1, action=2 (hangUp) — spec §3 golden frame, CRC 0x1FBD.
    private val golden = hex(
        "AA 19 01 00 00 00 00 02 00 00 00 00 00 00 00 01 00 00 00 2A 00 00 00 01 00 02 00 BD 1F"
    )

    @Test
    fun `decodes the spec golden frame byte-exactly`() {
        val event = CallControlSchema.decode(golden)
        assertNotNull(event)
        assertEquals(42L, event!!.id)
        assertEquals(1, event.seq)
        assertEquals(CallControlSchema.ACTION_HANG_UP, event.action)
    }

    @Test
    fun `golden frame round-trips through the shared FrameEncoder`() {
        // Rebuild the frame from its payload with the app's own encoder — proves the
        // control channel uses the exact same wrapper/CRC as every other channel.
        val payload = golden.copyOfRange(2, golden.size - 2)
        assertEquals(golden.toList(), FrameEncoder.encode(payload).toList())
    }

    @Test
    fun `corrupted CRC is rejected`() {
        val bad = golden.copyOf().also { it[it.size - 1] = 0x00 }
        assertNull(CallControlSchema.decode(bad))
    }

    @Test
    fun `wrong sync byte is rejected`() {
        val bad = golden.copyOf().also { it[0] = 0x55 }
        assertNull(CallControlSchema.decode(bad))
    }

    @Test
    fun `truncated frame is rejected`() {
        assertNull(CallControlSchema.decode(golden.copyOfRange(0, 10)))
    }

    @Test
    fun `unknown ctrlType is rejected`() {
        // Flip the tag and fix the CRC so ONLY the type check can reject it.
        val payload = golden.copyOfRange(2, golden.size - 2).also { it[0] = 0x7F }
        assertNull(CallControlSchema.decode(FrameEncoder.encode(payload)))
    }

    @Test
    fun `appended fields (bigger LEN) still decode - forward compatible`() {
        // Future firmware appends a data word: same offsets, larger section + LEN.
        val capnp = ByteArray(8 + 8 + 16).also { b ->
            b[4] = 3            // segment size = root + 2 data words
            b[8 + 4] = 2        // root ptr: dataWords = 2
            b[16 + 0] = 7       // id = 7
            b[16 + 4] = 9       // seq = 9
            b[16 + 6] = 1       // action = answer
            b[16 + 8] = 0x5A    // appended future field — must be ignored
        }
        val frame = FrameEncoder.encode(byteArrayOf(0x01) + capnp)
        val event = CallControlSchema.decode(frame)
        assertNotNull(event)
        assertEquals(7L, event!!.id)
        assertEquals(9, event.seq)
        assertEquals(CallControlSchema.ACTION_ANSWER, event.action)
    }

    private fun hex(s: String): ByteArray =
        s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
