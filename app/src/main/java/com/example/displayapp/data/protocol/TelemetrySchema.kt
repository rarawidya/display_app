package com.example.displayapp.data.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manual Cap'n Proto wire-format reader/writer for the EVdisplay `VotolTelemetry`
 * message (docs/capnpble.md §3, file id `0xf0e5f2ff4f178d2a`).
 *
 * Produces and consumes messages in Cap'n Proto's standard *unpacked*,
 * single-segment serialization format, wire-compatible with the board's
 * capnp-generated code.
 *
 * ⚠️ **2026-07-07 wire-breaking renumber.** `VotolTelemetry` was re-sorted to
 * sequential ordinals @0..@14 (declaration order) and gained `batteryCurrent`,
 * `tempBattery`, `odoMeters`, `tripMeters`. Every byte offset moved; the data
 * section grew 24→32 bytes (3→4 words) and the framed payload 40→48 bytes
 * (LEN `0x30`). @0..@14 are now FROZEN — new fields append at @15+.
 *
 * Cap'n Proto message format:
 *   [Segment Table]  - 8 bytes (1 segment): uint32(0) + uint32(segment_size_words=5)
 *   [Root Pointer]   - 8 bytes: struct pointer (offset=0, dataSize=4, ptrSize=0)
 *   [Struct Data]    - 32 bytes (4 words × 8 bytes)
 *
 * Data layout (byte offsets within the 32-byte data section; add 16 for the
 * payload-absolute offset) — see docs/capnpble.md §3/§7:
 *   [0..1]   batteryVolt    : UInt16   (0.1 V units)
 *   [2]      batteryPercent : UInt8    (0-100 %, 255 = not-yet-known)
 *   [3]      batteryCurrent : Int8     (whole A, signed, positive = charging)
 *   [4..5]   currentMotor   : Int16    (deci-amps, ÷10 = A, signed; neg = regen)
 *   [6..7]   rpm            : UInt16   (real motor RPM)
 *   [8..9]   kmh            : UInt16   (km/h)
 *   [10]     tempControl    : Int8     (controller °C)
 *   [11]     tempMotor      : Int8     (motor °C)
 *   [12]     tempBattery    : Int8     (battery pack °C)
 *   [13]     driveMode      : UInt8    (1 Eco / 2 Urban / 3 Sport)
 *   [14]     flags          : UInt8    (bit0 run, 1 brake, 2 moving, 3 reverse, …)
 *   [15]     padding        (0)
 *   [16..19] faultCode      : UInt32   (bitfield, currently 0)
 *   [20..23] seq            : UInt32   (rolling frame counter)
 *   [24..27] odoMeters      : UInt32   (lifetime odometer, metres → ÷1000 = km)
 *   [28..31] tripMeters     : UInt32   (resettable trip odometer, metres → ÷1000 = km)
 *
 * Total message size: 8 (header) + 8 (pointer) + 32 (data) = 48 bytes. A proper
 * Cap'n Proto reader ignores unknown trailing bytes, so honor the frame LEN
 * rather than assuming 48 — [readFrom] reads only the offsets it knows and
 * tolerates a longer payload.
 */
object TelemetrySchema {

    private const val DATA_WORDS = 4
    private const val PTR_WORDS = 0
    private const val STRUCT_DATA_SIZE = DATA_WORDS * 8 // 32 bytes
    private const val HEADER_SIZE = 8  // segment table
    private const val POINTER_SIZE = 8 // root struct pointer
    const val MESSAGE_SIZE = HEADER_SIZE + POINTER_SIZE + STRUCT_DATA_SIZE // 48 bytes

    // Offsets within the struct data section (relative to struct start).
    private const val OFF_BATTERY_DECIVOLTS = 0
    private const val OFF_BATTERY_PERCENT = 2
    private const val OFF_BATTERY_CURRENT = 3
    private const val OFF_CURRENT_MOTOR = 4
    private const val OFF_RPM = 6
    private const val OFF_SPEED_KMH = 8
    private const val OFF_CONTROLLER_TEMP = 10
    private const val OFF_MOTOR_TEMP = 11
    private const val OFF_BATTERY_TEMP = 12
    private const val OFF_DRIVE_MODE = 13
    private const val OFF_FLAGS = 14
    private const val OFF_FAULT_CODE = 16
    private const val OFF_SEQ = 20
    private const val OFF_ODO_METERS = 24
    private const val OFF_TRIP_METERS = 28

    /**
     * Reads a [VotolTelemetry] from a Cap'n Proto serialized message. Reads only
     * the ordinals it knows; a longer payload (schema grown per docs/capnpble.md
     * §3) is tolerated.
     */
    fun readFrom(payload: ByteArray): VotolTelemetry {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Skip segment table (8 bytes) + root pointer (8 bytes).
        val dataOffset = HEADER_SIZE + POINTER_SIZE

        return VotolTelemetry(
            batteryDeciVolts = buf.getShort(dataOffset + OFF_BATTERY_DECIVOLTS).toInt() and 0xFFFF,
            batteryPercent = buf.get(dataOffset + OFF_BATTERY_PERCENT).toInt() and 0xFF,
            batteryCurrent = buf.get(dataOffset + OFF_BATTERY_CURRENT).toInt(), // signed Int8
            currentMotor = buf.getShort(dataOffset + OFF_CURRENT_MOTOR).toInt(),
            rpm = buf.getShort(dataOffset + OFF_RPM).toInt() and 0xFFFF,
            speedKmh = buf.getShort(dataOffset + OFF_SPEED_KMH).toInt() and 0xFFFF,
            controllerTempC = buf.get(dataOffset + OFF_CONTROLLER_TEMP).toInt(),
            motorTempC = buf.get(dataOffset + OFF_MOTOR_TEMP).toInt(),
            batteryTempC = buf.get(dataOffset + OFF_BATTERY_TEMP).toInt(),
            driveMode = buf.get(dataOffset + OFF_DRIVE_MODE).toInt() and 0xFF,
            flags = buf.get(dataOffset + OFF_FLAGS).toInt() and 0xFF,
            faultCode = buf.getInt(dataOffset + OFF_FAULT_CODE).toLong() and 0xFFFFFFFFL,
            seq = buf.getInt(dataOffset + OFF_SEQ).toLong() and 0xFFFFFFFFL,
            odoMeters = buf.getInt(dataOffset + OFF_ODO_METERS).toLong() and 0xFFFFFFFFL,
            tripMeters = buf.getInt(dataOffset + OFF_TRIP_METERS).toLong() and 0xFFFFFFFFL
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
        val batteryPercent: Int,
        val batteryCurrent: Int,
        val currentMotor: Int,
        val rpm: Int,
        val speedKmh: Int,
        val controllerTempC: Int,
        val motorTempC: Int,
        val batteryTempC: Int,
        val driveMode: Int,
        val flags: Int,
        val faultCode: Long,
        val seq: Long,
        val odoMeters: Long,
        val tripMeters: Long
    )

    class MessageBuilder {
        private var batteryDeciVolts: Short = 0
        private var batteryPercent: Byte = 0
        private var batteryCurrent: Byte = 0
        private var currentMotor: Short = 0
        private var rpm: Short = 0
        private var speedKmh: Short = 0
        private var controllerTempC: Byte = 0
        private var motorTempC: Byte = 0
        private var batteryTempC: Byte = 0
        private var driveMode: Byte = 0
        private var flags: Byte = 0
        private var faultCode: Int = 0
        private var seq: Int = 0
        private var odoMeters: Int = 0
        private var tripMeters: Int = 0

        fun setBatteryDeciVolts(value: Short) { batteryDeciVolts = value }
        fun setBatteryPercent(value: Byte) { batteryPercent = value }
        fun setBatteryCurrent(value: Byte) { batteryCurrent = value }
        fun setCurrentMotor(value: Short) { currentMotor = value }
        fun setRpm(value: Short) { rpm = value }
        fun setSpeedKmh(value: Short) { speedKmh = value }
        fun setControllerTempC(value: Byte) { controllerTempC = value }
        fun setMotorTempC(value: Byte) { motorTempC = value }
        fun setBatteryTempC(value: Byte) { batteryTempC = value }
        fun setDriveMode(value: Byte) { driveMode = value }
        fun setFlags(value: Byte) { flags = value }
        fun setFaultCode(value: Int) { faultCode = value }
        fun setSeq(value: Int) { seq = value }
        fun setOdoMeters(value: Int) { odoMeters = value }
        fun setTripMeters(value: Int) { tripMeters = value }

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

            // Struct data section (see layout doc above). Written in offset order so
            // the buffer position lands exactly on each field's byte offset.
            val dataStart = buf.position()
            buf.putShort(batteryDeciVolts)  // [0..1]
            buf.put(batteryPercent)         // [2]
            buf.put(batteryCurrent)         // [3]
            buf.putShort(currentMotor)      // [4..5]
            buf.putShort(rpm)               // [6..7]
            buf.putShort(speedKmh)          // [8..9]
            buf.put(controllerTempC)        // [10]
            buf.put(motorTempC)             // [11]
            buf.put(batteryTempC)           // [12]
            buf.put(driveMode)              // [13]
            buf.put(flags)                  // [14]
            buf.put(0)                      // [15] padding
            buf.putInt(faultCode)           // [16..19]
            buf.putInt(seq)                 // [20..23]
            buf.putInt(odoMeters)           // [24..27]
            buf.putInt(tripMeters)          // [28..31]

            // Pad any remaining reserved bytes to zero (position should already be 32).
            while (buf.position() < dataStart + STRUCT_DATA_SIZE) {
                buf.put(0)
            }

            return buf.array()
        }
    }
}
