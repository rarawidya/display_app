package com.example.displayapp.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual Cap'n Proto wire-format **writer** for the phone→board navigation
 * command messages (`app/schema/navigation.capnp`, schema id `0xc4e1a9f30b7d2266`;
 * see docs/NAVIGATION-INTEGRATION.md).
 *
 * Same family as [PhoneNotificationSchema] but a separate message set on a separate
 * BLE characteristic (`0xAF06`). Each message is one unpacked, single-segment Cap'n
 * Proto struct with exactly one `Text` pointer field, prefixed by a 1-byte `navType`
 * tag, then wrapped by [FrameEncoder.encode] into the shared
 * `[0xAA][LEN][navType‖capnp][CRC16-LE]` frame the board expects in one BLE write.
 *
 * Struct data layout is FROZEN and matches what `capnp compile` produces for the
 * schema (ordinals assigned in descending field size so the layout is compact and
 * hole-free). Verified byte-exact against the reference compiler in
 * `NavigationFrameTest`.
 *
 * Text is always emitted as a non-null pointer to a `List(UInt8)` of the UTF-8 bytes
 * plus a NUL terminator (`listCount = bytes + 1`, minimum 1) — this matches
 * `capnp encode` exactly, including the empty-string case (pointer to a 1-byte NUL
 * list), so terminal frames with no street name stay byte-identical to the compiler.
 */
object NavigationSchema {

    /** `navType` tag = first payload byte (navigation.capnp framing). */
    const val NAV_TYPE_ROUTE_SUMMARY = 0x01
    const val NAV_TYPE_NAV_INSTRUCTION = 0x02
    const val NAV_TYPE_ROUTE_CHUNK = 0x03 // reserved (phase 2, not encoded here)

    /** UTF-8 byte caps — pre-truncate so the whole frame fits the MTU (≤ 240 B payload). */
    const val MAX_STREET_NAME_BYTES = 40
    const val MAX_DESTINATION_NAME_BYTES = 48

    private const val WORD = 8
    private const val PTR_WORDS = 1 // every nav struct has exactly one Text pointer

    // Cap'n Proto list-pointer sub-fields for a Text (= List(UInt8)).
    private const val LIST_POINTER_TYPE = 1L      // 0 = struct, 1 = list
    private const val LIST_ELEMENT_SIZE_BYTE = 2L // capnp elementSize enum: 2 = 1 byte

    /**
     * Fields of `NavInstruction` (navType 0x02). Integers are widened to [Long]/[Int]
     * so callers pass unsigned wire values without sign hassle; [encode] writes the
     * low 16/32 bits per the schema type. `state`/`maneuver`/`nextManeuver` are the
     * capnp enum **wire values** (16-bit) — map from the domain enums at the call site.
     */
    data class NavInstruction(
        val routeId: Long,
        val distanceRemainingM: Long,
        val etaSeconds: Long,
        val seq: Int,
        val state: Int,
        val maneuver: Int,
        val distanceToTurnM: Int,
        val nextManeuver: Int,
        val nextDistanceM: Int,
        val roundaboutExit: Int,
        val speedLimitKmh: Int,
        val streetName: String,
    )

    /** Fields of `RouteSummary` (navType 0x01). */
    data class RouteSummary(
        val routeId: Long,
        val totalDistanceM: Long,
        val totalDurationSec: Long,
        val maneuverCount: Int,
        val polylineChunks: Int,
        val schemaVersion: Int,
        val destinationName: String,
    )

    /** Full BLE frame (ready to write to 0xAF06) for a [NavInstruction]. */
    fun frameNavInstruction(n: NavInstruction): ByteArray =
        FrameEncoder.encode(payloadNavInstruction(n))

    /** Full BLE frame (ready to write to 0xAF06) for a [RouteSummary]. */
    fun frameRouteSummary(r: RouteSummary): ByteArray =
        FrameEncoder.encode(payloadRouteSummary(r))

    /** `[navType][capnp]` payload for a [NavInstruction] (NavInstruction = 4 data words). */
    fun payloadNavInstruction(n: NavInstruction): ByteArray =
        encodeStruct(
            navType = NAV_TYPE_NAV_INSTRUCTION,
            dataWords = 4,
            text = sanitizeAndTruncate(n.streetName, MAX_STREET_NAME_BYTES),
        ) { b ->
            b.putInt(n.routeId.toInt())            // @0  bytes 0..3
            b.putInt(n.distanceRemainingM.toInt()) // @1  bytes 4..7
            b.putInt(n.etaSeconds.toInt())         // @2  bytes 8..11
            b.putShort(n.seq.toShort())            // @3  bytes 12..13
            b.putShort(n.state.toShort())          // @4  bytes 14..15
            b.putShort(n.maneuver.toShort())       // @5  bytes 16..17
            b.putShort(n.distanceToTurnM.toShort())// @6  bytes 18..19
            b.putShort(n.nextManeuver.toShort())   // @7  bytes 20..21
            b.putShort(n.nextDistanceM.toShort())  // @8  bytes 22..23
            b.put(n.roundaboutExit.toByte())       // @9  byte 24
            b.put(n.speedLimitKmh.toByte())        // @10 byte 25
            // bytes 26..31 padded to the 4-word boundary by encodeStruct
        }

    /** `[navType][capnp]` payload for a [RouteSummary] (RouteSummary = 2 data words). */
    fun payloadRouteSummary(r: RouteSummary): ByteArray =
        encodeStruct(
            navType = NAV_TYPE_ROUTE_SUMMARY,
            dataWords = 2,
            text = sanitizeAndTruncate(r.destinationName, MAX_DESTINATION_NAME_BYTES),
        ) { b ->
            b.putInt(r.routeId.toInt())            // @0  bytes 0..3
            b.putInt(r.totalDistanceM.toInt())     // @1  bytes 4..7
            b.putInt(r.totalDurationSec.toInt())   // @2  bytes 8..11
            b.putShort(r.maneuverCount.toShort())  // @3  bytes 12..13
            b.put(r.polylineChunks.toByte())       // @4  byte 14
            b.put(r.schemaVersion.toByte())        // @5  byte 15
        }

    /**
     * Serialize a struct with [dataWords] data words + exactly one trailing Text
     * pointer, prefixed with [navType]. [writeData] must write ≤ `dataWords*8` bytes
     * in ordinal-offset order; the remainder is zero-padded.
     */
    private inline fun encodeStruct(
        navType: Int,
        dataWords: Int,
        text: ByteArray,
        writeData: (ByteBuffer) -> Unit,
    ): ByteArray {
        val listCount = text.size + 1                   // + NUL terminator (matches capnp encode)
        val wordLen = (listCount + (WORD - 1)) / WORD    // text region words (>= 1)
        val segmentWords = 1 + dataWords + PTR_WORDS + wordLen // root + data + ptr + text

        val buf = ByteBuffer.allocate(WORD + segmentWords * WORD).order(ByteOrder.LITTLE_ENDIAN)

        // Segment table: one segment (count-1 = 0), size in words.
        buf.putInt(0)
        buf.putInt(segmentWords)

        // Root struct pointer: offset 0, dataWords, ptrWords.
        val root = 0L or (dataWords.toLong() shl 32) or (PTR_WORDS.toLong() shl 48)
        buf.putLong(root)

        // Struct data section (word-padded).
        val dataStart = buf.position()
        writeData(buf)
        while (buf.position() < dataStart + dataWords * WORD) buf.put(0)

        // Pointer section (one Text/list pointer at slot 0).
        val pointerWord = 1 + dataWords
        val textStartWord = 1 + dataWords + PTR_WORDS
        val offsetWords = (textStartWord - (pointerWord + 1)).toLong()
        val low = ((offsetWords shl 2) or LIST_POINTER_TYPE) and 0xFFFFFFFFL
        val high = ((listCount.toLong() shl 3) or LIST_ELEMENT_SIZE_BYTE) and 0xFFFFFFFFL
        buf.putLong(low or (high shl 32))

        // Text data region (NUL-terminated, word-padded).
        buf.put(text)
        repeat(wordLen * WORD - text.size) { buf.put(0) }

        val capnp = buf.array()
        return ByteArray(1 + capnp.size).also {
            it[0] = navType.toByte()
            System.arraycopy(capnp, 0, it, 1, capnp.size)
        }
    }

    /**
     * UTF-8 encode, replace control chars with spaces (the board sanitizes the same
     * way), then truncate to [maxBytes] without splitting a multi-byte codepoint.
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
