package com.example.displayapp.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual Cap'n Proto wire-format **writer** for the board's `PhoneNotification`
 * message (docs/APP-NOTIFICATION-INTEGRATION.md §2, schema id `0xb39c7a21e4d05f68`).
 *
 * Unlike [TelemetrySchema] (a pure-data struct with 0 pointer words), this message
 * carries three **Text** fields, so the encoder emits a 2-word data section, a
 * 3-word pointer section (one `List(UInt8)` pointer per Text), and a variable
 * text-data region laid out after the pointers. Empty strings are written as
 * **null pointers** — a conformant reader returns `""` for those.
 *
 * Frozen schema (@0..@6):
 * ```
 *   data word0: id:UInt32 @0..3, timestampUnix:UInt32 @4..7
 *   data word1: category:UInt8 @8, flags:UInt8 @9, (6 reserved bytes = 0)
 *   ptr  word0: appName  (Text)
 *   ptr  word1: title    (Text)
 *   ptr  word2: body     (Text)
 *   text region: each Text = UTF-8 bytes + NUL, zero-padded to an 8-byte word
 * ```
 *
 * Wrap the result with [FrameEncoder.encode] to get the `[0xAA][LEN][payload][CRC16-LE]`
 * frame the board expects in one BLE write to `0xAF07`. Verified byte-exact against
 * the spec's §5a golden frame in `PhoneNotificationFrameTest`.
 */
object PhoneNotificationSchema {

    /** UTF-8 byte caps per §2b — pre-truncate so the whole frame fits the MTU. */
    const val MAX_APP_NAME_BYTES = 24
    const val MAX_TITLE_BYTES = 48
    const val MAX_BODY_BYTES = 96

    /** `flags` bitfield (§2b / §4). */
    const val FLAG_ONGOING = 0x01   // persistent (active/incoming call); send a removed/clear when it ends
    const val FLAG_REMOVED = 0x02   // dismiss — collapses the currently-shown banner
    const val FLAG_SILENT = 0x04    // parsed by the board, not yet visually differentiated

    /** `category` values (§3). */
    const val CATEGORY_OTHER = 0
    const val CATEGORY_CALL = 1
    const val CATEGORY_MESSAGE_SMS = 2
    const val CATEGORY_MESSAGING_APP = 3
    const val CATEGORY_CLEAR_ALL = 6

    /**
     * Board **control command**, not a banner (capnpble.md §5b). The command name
     * rides in `title` (e.g. `"ODO_RESET_TRIP"`), `appName = "EVD"`, `body` ignored.
     */
    const val CATEGORY_CONTROL = 32

    private const val DATA_WORDS = 2
    private const val PTR_WORDS = 3
    private const val WORD = 8

    // Cap'n Proto list-pointer sub-fields for a Text (= List(UInt8)).
    private const val LIST_POINTER_TYPE = 1L        // 0 = struct, 1 = list
    private const val LIST_ELEMENT_SIZE_BYTE = 2L   // capnp elementSize enum: 2 = 1 byte

    /**
     * The 7 app-controlled fields (§2b). Ordinals @0..@6 are frozen; text is
     * pre-truncated by [encode] to the byte caps above.
     */
    data class PhoneNotification(
        val id: Int,
        val timestampUnix: Int = 0,
        val category: Int,
        val flags: Int = 0,
        val appName: String = "",
        val title: String = "",
        val body: String = ""
    )

    /** Serialize [n] to an unpacked, single-segment Cap'n Proto payload. */
    fun encode(n: PhoneNotification): ByteArray {
        // UTF-8 text, control chars → space, truncated on a codepoint boundary.
        val texts = listOf(
            sanitizeAndTruncate(n.appName, MAX_APP_NAME_BYTES),
            sanitizeAndTruncate(n.title, MAX_TITLE_BYTES),
            sanitizeAndTruncate(n.body, MAX_BODY_BYTES)
        )

        // Lay out each non-empty Text as a word-aligned region after the pointer
        // section; empty Texts become null pointers and occupy no space.
        val pointerSectionStartWord = 1 + DATA_WORDS            // root(1) + data(2) → word 3
        val textStartWord = 1 + DATA_WORDS + PTR_WORDS          // → word 6
        var cursor = textStartWord
        val regions = arrayOfNulls<TextRegion>(3)
        for (i in 0..2) {
            val bytes = texts[i]
            if (bytes.isEmpty()) continue
            val listCount = bytes.size + 1                       // include NUL terminator
            val wordLen = (listCount + (WORD - 1)) / WORD
            regions[i] = TextRegion(startWord = cursor, listCount = listCount, wordLen = wordLen)
            cursor += wordLen
        }
        val segmentWords = cursor

        val buf = ByteBuffer.allocate(WORD + segmentWords * WORD).order(ByteOrder.LITTLE_ENDIAN)

        // Segment table: one segment (count-1 = 0), size in words.
        buf.putInt(0)
        buf.putInt(segmentWords)

        // Root struct pointer: offset 0, data = 2 words, pointers = 3 words.
        val root = 0L or (DATA_WORDS.toLong() shl 32) or (PTR_WORDS.toLong() shl 48)
        buf.putLong(root)

        // Struct data section (2 words).
        buf.putInt(n.id)                        // @0  bytes 0..3
        buf.putInt(n.timestampUnix)             // @1  bytes 4..7
        buf.put((n.category and 0xFF).toByte()) // @2  byte 8
        buf.put((n.flags and 0xFF).toByte())    // @3  byte 9
        repeat(6) { buf.put(0) }                // reserved bytes 10..15

        // Pointer section: one Text (list) pointer per field, in ordinal order.
        for (i in 0..2) {
            val r = regions[i]
            if (r == null) {
                buf.putLong(0L)                 // null pointer → reader yields ""
            } else {
                val pointerWord = pointerSectionStartWord + i
                val offsetWords = r.startWord - (pointerWord + 1)
                val low = ((offsetWords.toLong() shl 2) or LIST_POINTER_TYPE) and 0xFFFFFFFFL
                val high = ((r.listCount.toLong() shl 3) or LIST_ELEMENT_SIZE_BYTE) and 0xFFFFFFFFL
                buf.putLong(low or (high shl 32))
            }
        }

        // Text data regions (word-aligned, NUL-terminated).
        for (i in 0..2) {
            val r = regions[i] ?: continue
            val bytes = texts[i]
            buf.put(bytes)
            repeat(r.wordLen * WORD - bytes.size) { buf.put(0) }  // NUL terminator + padding
        }

        return buf.array()
    }

    private data class TextRegion(val startWord: Int, val listCount: Int, val wordLen: Int)

    /**
     * UTF-8 encode, replacing control chars with spaces (the board sanitizes the
     * same way), then truncate to [maxBytes] without splitting a multi-byte
     * codepoint (back the cut point off any UTF-8 continuation byte).
     */
    private fun sanitizeAndTruncate(raw: String, maxBytes: Int): ByteArray {
        val cleaned = buildString(raw.length) {
            for (ch in raw) append(if (ch.code < 0x20 || ch.code == 0x7F) ' ' else ch)
        }
        val bytes = cleaned.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }
}
