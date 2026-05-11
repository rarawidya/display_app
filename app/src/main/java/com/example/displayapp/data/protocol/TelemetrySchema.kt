package com.example.displayapp.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual Cap'n Proto wire-format reader/writer for TelemetryFrame.
 *
 * Produces and consumes messages in Cap'n Proto's standard serialization format,
 * wire-compatible with any capnp-generated code (C, C++, Rust, etc.) on the MCU side.
 *
 * Cap'n Proto message format:
 *   [Segment Table]  - 8 bytes (1 segment): uint32(0) + uint32(segment_size_words)
 *   [Root Pointer]   - 8 bytes: struct pointer (offset=0, dataSize=3, ptrSize=0)
 *   [Struct Data]    - 24 bytes (3 words × 8 bytes)
 *
 * TelemetryFrame struct layout (3 data words, 0 pointer words):
 *   Word 0 (bytes 0-7):
 *     [0..3] timestamp   : UInt32
 *     [4..5] speed       : UInt16
 *     [6]    battery     : UInt8
 *     [7]    temperature : Int8
 *   Word 1 (bytes 8-15):
 *     [8..9]   voltage   : UInt16
 *     [10..11] current   : Int16
 *     [12..15] odometer  : UInt32
 *   Word 2 (bytes 16-23):
 *     [16..17] mode       : UInt16 (enum)
 *     [18]     indicators : UInt8
 *     [19..23] reserved
 *
 * Total message size: 8 (header) + 8 (pointer) + 24 (data) = 40 bytes
 */
object TelemetrySchema {

    private const val DATA_WORDS = 3
    private const val PTR_WORDS = 0
    private const val STRUCT_DATA_SIZE = DATA_WORDS * 8 // 24 bytes
    private const val HEADER_SIZE = 8  // segment table
    private const val POINTER_SIZE = 8 // root struct pointer
    const val MESSAGE_SIZE = HEADER_SIZE + POINTER_SIZE + STRUCT_DATA_SIZE // 40 bytes

    // Offsets within the struct data section (relative to struct start)
    private const val OFF_TIMESTAMP = 0
    private const val OFF_SPEED = 4
    private const val OFF_BATTERY = 6
    private const val OFF_TEMPERATURE = 7
    private const val OFF_VOLTAGE = 8
    private const val OFF_CURRENT = 10
    private const val OFF_ODOMETER = 12
    private const val OFF_MODE = 16
    private const val OFF_INDICATORS = 18

    /**
     * Reads a TelemetryFrame from a Cap'n Proto serialized message.
     */
    fun readFrom(payload: ByteArray): TelemetryFrame {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Skip segment table (8 bytes) + root pointer (8 bytes)
        val dataOffset = HEADER_SIZE + POINTER_SIZE

        return TelemetryFrame(
            timestamp = buf.getInt(dataOffset + OFF_TIMESTAMP).toLong() and 0xFFFFFFFFL,
            speed = buf.getShort(dataOffset + OFF_SPEED).toInt() and 0xFFFF,
            battery = buf.get(dataOffset + OFF_BATTERY).toInt() and 0xFF,
            temperature = buf.get(dataOffset + OFF_TEMPERATURE).toInt(),
            voltage = buf.getShort(dataOffset + OFF_VOLTAGE).toInt() and 0xFFFF,
            current = buf.getShort(dataOffset + OFF_CURRENT).toInt(),
            odometer = buf.getInt(dataOffset + OFF_ODOMETER).toLong() and 0xFFFFFFFFL,
            mode = buf.getShort(dataOffset + OFF_MODE).toInt() and 0xFFFF,
            indicators = buf.get(dataOffset + OFF_INDICATORS).toInt() and 0xFF
        )
    }

    /**
     * Builds a Cap'n Proto serialized message from field values.
     */
    fun buildMessage(block: MessageBuilder.() -> Unit): ByteArray {
        val builder = MessageBuilder()
        builder.block()
        return builder.serialize()
    }

    data class TelemetryFrame(
        val timestamp: Long,
        val speed: Int,
        val battery: Int,
        val temperature: Int,
        val voltage: Int,
        val current: Int,
        val odometer: Long,
        val mode: Int,
        val indicators: Int
    )

    class MessageBuilder {
        private var timestamp: Int = 0
        private var speed: Short = 0
        private var battery: Byte = 0
        private var temperature: Byte = 0
        private var voltage: Short = 0
        private var current: Short = 0
        private var odometer: Int = 0
        private var mode: Short = 0
        private var indicators: Byte = 0

        fun setTimestamp(value: Int) { timestamp = value }
        fun setSpeed(value: Short) { speed = value }
        fun setBattery(value: Byte) { battery = value }
        fun setTemperature(value: Byte) { temperature = value }
        fun setVoltage(value: Short) { voltage = value }
        fun setCurrent(value: Short) { current = value }
        fun setOdometer(value: Int) { odometer = value }
        fun setMode(value: Short) { mode = value }
        fun setIndicators(value: Byte) { indicators = value }

        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            // Segment table: 1 segment (count-1 = 0), size in words
            val segmentWords = 1 + DATA_WORDS + PTR_WORDS // pointer word + data words
            buf.putInt(0) // segment count - 1
            buf.putInt(segmentWords) // segment 0 size in words

            // Root struct pointer at segment start
            // Format: offset(30 bits) | type(2 bits=0 for struct) | dataSize(16) | ptrSize(16)
            // offset = 0 (struct immediately follows pointer)
            val structPointer: Long =
                (0L shl 2) or // offset = 0 words from end of pointer
                0L or          // type = 0 (struct)
                (DATA_WORDS.toLong() shl 32) or
                (PTR_WORDS.toLong() shl 48)
            buf.putLong(structPointer)

            // Struct data section
            val dataStart = buf.position()
            buf.putInt(timestamp)           // [0..3]
            buf.putShort(speed)             // [4..5]
            buf.put(battery)                // [6]
            buf.put(temperature)            // [7]
            buf.putShort(voltage)           // [8..9]
            buf.putShort(current)           // [10..11]
            buf.putInt(odometer)            // [12..15]
            buf.putShort(mode)              // [16..17]
            buf.put(indicators)             // [18]

            // Pad remaining bytes in word 2 to zero
            while (buf.position() < dataStart + STRUCT_DATA_SIZE) {
                buf.put(0)
            }

            return buf.array()
        }
    }
}
