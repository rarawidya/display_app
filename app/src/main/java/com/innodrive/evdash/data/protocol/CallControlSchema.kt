package com.innodrive.evdash.data.protocol

/**
 * Decoder for the board→phone **control uplink** (`0xAF05`,
 * docs/CALL-CONTROL-INTEGRATION.md): the cluster's call answer/end button taps.
 *
 * Frame: `[0xAA][LEN][ctrlType(1) ‖ capnp][CRC16-LE]`, CRC16-CCITT over
 * `LEN‖payload` — the same wrapper as every other channel, but this is the one
 * place the **phone decodes** it (everything else is phone→board, and telemetry
 * has its own streaming [FrameDecoder]; control arrives as one whole frame per
 * GATT notification, so a stateless per-frame parse is enough).
 *
 * `CallControl` capnp layout (ordinals FROZEN, one data word, no pointers):
 * ```
 *   data word0: id:UInt32 @0..3, seq:UInt16 @4..5, action:UInt8 @6, (pad @7)
 * ```
 * Fields are read at fixed offsets but `LEN` is honored — a future firmware that
 * appends fields (bigger data section) still decodes; unknown trailing bytes are
 * ignored, same rule as telemetry.
 */
object CallControlSchema {

    /** `ctrlType` tag = first payload byte. */
    const val CTRL_TYPE_CALL = 0x01

    /** `CallControl.action` values (spec §4). */
    const val ACTION_ANSWER = 1
    const val ACTION_HANG_UP = 2
    const val ACTION_DISMISS = 3

    /** Decoded board button event. */
    data class CallControl(
        /** `PhoneNotification.id` of the banner the buttons belong to (0 = current). */
        val id: Long,
        /** Rolling per-tap counter; a repeat of the previous value is a retransmit. */
        val seq: Int,
        val action: Int,
    )

    // Frame offsets: [0]=0xAA [1]=LEN [2]=ctrlType [3..10]=seg table [11..18]=root ptr
    // [19..]=data section. Minimum payload = 1 (ctrlType) + 24 (capnp: table+root+1 word).
    private const val SYNC = 0xAA
    private const val MIN_PAYLOAD = 1 + 8 + 8 + 8
    private const val DATA_START = 2 + 1 + 8 + 8

    /**
     * Parse one complete control frame. Returns null (never throws) on anything
     * malformed: wrong sync, truncated, CRC mismatch, or an unknown `ctrlType` —
     * callers log-and-drop, matching the board's own lenient RX behavior.
     */
    fun decode(frame: ByteArray): CallControl? {
        if (frame.size < 4) return null
        if ((frame[0].toInt() and 0xFF) != SYNC) return null
        val len = frame[1].toInt() and 0xFF
        if (len < MIN_PAYLOAD || frame.size < len + 4) return null

        // CRC16-CCITT over LEN‖payload, wire little-endian.
        val crc = Crc16.compute(frame, offset = 1, length = 1 + len)
        val wireCrc = (frame[2 + len].toInt() and 0xFF) or ((frame[3 + len].toInt() and 0xFF) shl 8)
        if (crc != wireCrc) return null

        if ((frame[2].toInt() and 0xFF) != CTRL_TYPE_CALL) return null

        return CallControl(
            id = readU32(frame, DATA_START),
            seq = readU16(frame, DATA_START + 4),
            action = frame[DATA_START + 6].toInt() and 0xFF,
        )
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
