package com.example.displayapp.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual Cap'n Proto wire-format reader/writer for the EVdashboard
 * `VotolTelemetry` message (capnp.md §3–§4).
 *
 * Produces and consumes messages in Cap'n Proto's standard *unpacked*,
 * single-segment serialization format, wire-compatible with the board's
 * capnp-generated code.
 *
 * Cap'n Proto message format:
 *   [Segment Table]  - 8 bytes (1 segment): uint32(0) + uint32(segment_size_words=4)
 *   [Root Pointer]   - 8 bytes: struct pointer (offset=0, dataSize=3, ptrSize=0)
 *   [Struct Data]    - 24 bytes (3 words × 8 bytes)
 *
 * VotolTelemetry data layout (3 data words, 0 pointer words) — byte offsets are
 * within the 24-byte data section (add 16 for the payload-absolute offset):
 *   [0..1]   batteryDeciVolts : UInt16   (0.1 V units)
 *   [2..3]   motorCurrentRaw  : Int16    (raw counts, currently 0)
 *   [4..5]   rpm              : UInt16
 *   [6..7]   speedKmh         : UInt16   (km/h, direct)
 *   [8]      controllerTempC  : Int8
 *   [9]      motorTempC       : Int8
 *   [10]     driveMode        : UInt8    (1 Eco / 2 Urban / 3 Sport)
 *   [11]     flags            : UInt8    (bit0 engineRunning, bit1 brake, bit2 moving, bit3 reverse)
 *   [12..15] faultCode        : UInt32   (bitfield, currently 0)
 *   [16..19] seq              : UInt32   (rolling frame counter)
 *   [20]     batteryPercent   : UInt8    (0-100, or 255 = not yet known)
 *   [21..23] padding          (0)
 *
 * Total message size: 8 (header) + 8 (pointer) + 24 (data) = 40 bytes. A proper
 * Cap'n Proto reader ignores unknown trailing bytes, so honor the frame LEN
 * rather than assuming 40 (capnp.md §5) — [readFrom] reads only the offsets it
 * knows and tolerates a longer payload.
 */
object TelemetrySchema {

    private const val DATA_WORDS = 3
    private const val PTR_WORDS = 0
    private const val STRUCT_DATA_SIZE = DATA_WORDS * 8 // 24 bytes
    private const val HEADER_SIZE = 8  // segment table
    private const val POINTER_SIZE = 8 // root struct pointer
    const val MESSAGE_SIZE = HEADER_SIZE + POINTER_SIZE + STRUCT_DATA_SIZE // 40 bytes

    // Offsets within the struct data section (relative to struct start).
    private const val OFF_BATTERY_DECIVOLTS = 0
    private const val OFF_MOTOR_CURRENT_RAW = 2
    private const val OFF_RPM = 4
    private const val OFF_SPEED_KMH = 6
    private const val OFF_CONTROLLER_TEMP = 8
    private const val OFF_MOTOR_TEMP = 9
    private const val OFF_DRIVE_MODE = 10
    private const val OFF_FLAGS = 11
    private const val OFF_FAULT_CODE = 12
    private const val OFF_SEQ = 16
    private const val OFF_BATTERY_PERCENT = 20

    /**
     * Reads a [VotolTelemetry] from a Cap'n Proto serialized message. Reads only
     * the ordinals it knows; a longer payload (schema grown per capnp.md §5) is
     * tolerated.
     */
    fun readFrom(payload: ByteArray): VotolTelemetry {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Skip segment table (8 bytes) + root pointer (8 bytes).
        val dataOffset = HEADER_SIZE + POINTER_SIZE

        return VotolTelemetry(
            batteryDeciVolts = buf.getShort(dataOffset + OFF_BATTERY_DECIVOLTS).toInt() and 0xFFFF,
            motorCurrentRaw = buf.getShort(dataOffset + OFF_MOTOR_CURRENT_RAW).toInt(),
            rpm = buf.getShort(dataOffset + OFF_RPM).toInt() and 0xFFFF,
            speedKmh = buf.getShort(dataOffset + OFF_SPEED_KMH).toInt() and 0xFFFF,
            controllerTempC = buf.get(dataOffset + OFF_CONTROLLER_TEMP).toInt(),
            motorTempC = buf.get(dataOffset + OFF_MOTOR_TEMP).toInt(),
            driveMode = buf.get(dataOffset + OFF_DRIVE_MODE).toInt() and 0xFF,
            flags = buf.get(dataOffset + OFF_FLAGS).toInt() and 0xFF,
            faultCode = buf.getInt(dataOffset + OFF_FAULT_CODE).toLong() and 0xFFFFFFFFL,
            seq = buf.getInt(dataOffset + OFF_SEQ).toLong() and 0xFFFFFFFFL,
            batteryPercent = buf.get(dataOffset + OFF_BATTERY_PERCENT).toInt() and 0xFF
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

    data class VotolTelemetry(
        val batteryDeciVolts: Int,
        val motorCurrentRaw: Int,
        val rpm: Int,
        val speedKmh: Int,
        val controllerTempC: Int,
        val motorTempC: Int,
        val driveMode: Int,
        val flags: Int,
        val faultCode: Long,
        val seq: Long,
        val batteryPercent: Int
    )

    class MessageBuilder {
        private var batteryDeciVolts: Short = 0
        private var motorCurrentRaw: Short = 0
        private var rpm: Short = 0
        private var speedKmh: Short = 0
        private var controllerTempC: Byte = 0
        private var motorTempC: Byte = 0
        private var driveMode: Byte = 0
        private var flags: Byte = 0
        private var faultCode: Int = 0
        private var seq: Int = 0
        private var batteryPercent: Byte = 0

        fun setBatteryDeciVolts(value: Short) { batteryDeciVolts = value }
        fun setMotorCurrentRaw(value: Short) { motorCurrentRaw = value }
        fun setRpm(value: Short) { rpm = value }
        fun setSpeedKmh(value: Short) { speedKmh = value }
        fun setControllerTempC(value: Byte) { controllerTempC = value }
        fun setMotorTempC(value: Byte) { motorTempC = value }
        fun setDriveMode(value: Byte) { driveMode = value }
        fun setFlags(value: Byte) { flags = value }
        fun setFaultCode(value: Int) { faultCode = value }
        fun setSeq(value: Int) { seq = value }
        fun setBatteryPercent(value: Byte) { batteryPercent = value }

        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            // Segment table: 1 segment (count-1 = 0), size in words.
            val segmentWords = 1 + DATA_WORDS + PTR_WORDS // pointer word + data words
            buf.putInt(0) // segment count - 1
            buf.putInt(segmentWords) // segment 0 size in words

            // Root struct pointer at segment start.
            // Format: offset(30 bits) | type(2 bits=0 struct) | dataSize(16) | ptrSize(16)
            val structPointer: Long =
                (0L shl 2) or // offset = 0 words from end of pointer
                0L or          // type = 0 (struct)
                (DATA_WORDS.toLong() shl 32) or
                (PTR_WORDS.toLong() shl 48)
            buf.putLong(structPointer)

            // Struct data section (see layout doc above).
            val dataStart = buf.position()
            buf.putShort(batteryDeciVolts)  // [0..1]
            buf.putShort(motorCurrentRaw)   // [2..3]
            buf.putShort(rpm)               // [4..5]
            buf.putShort(speedKmh)          // [6..7]
            buf.put(controllerTempC)        // [8]
            buf.put(motorTempC)             // [9]
            buf.put(driveMode)              // [10]
            buf.put(flags)                  // [11]
            buf.putInt(faultCode)           // [12..15]
            buf.putInt(seq)                 // [16..19]
            buf.put(batteryPercent)         // [20]

            // Pad remaining reserved bytes in word 2 to zero.
            while (buf.position() < dataStart + STRUCT_DATA_SIZE) {
                buf.put(0)
            }

            return buf.array()
        }
    }
}
