package com.innodrive.evdash.data.protocol

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
 * (LEN `0x30`). @0..@14 are now FROZEN.
 *
 * **Append-only extensions (docs/capnpble_new.md §3, @0..@23 now frozen):** trip
 * A/B @15/@16 (2026-07-07), OTA @17..@19 (2026-07-13), multi-motor @20..@23
 * (2026-07-14). The data section grew 32→56 bytes (4→7 words) and the framed
 * payload 48→72 bytes (LEN `0x48`, 76-byte frame). @17..@23 are BOARD→app TX-only.
 *
 * Cap'n Proto message format:
 *   [Segment Table]  - 8 bytes (1 segment): uint32(0) + uint32(segment_size_words=8)
 *   [Root Pointer]   - 8 bytes: struct pointer (offset=0, dataSize=7, ptrSize=0)
 *   [Struct Data]    - 56 bytes (7 words × 8 bytes)
 *
 * Data layout (byte offsets within the 56-byte data section; add 16 for the
 * payload-absolute offset) — see docs/capnpble_new.md §3:
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
 *   [28..31] tripMeters     : UInt32   (LEGACY ALIAS of tripAMeters, metres)
 *   [32..35] tripAMeters    : UInt32   (trip A, metres → ÷1000 = km, resettable)
 *   [36..39] tripBMeters    : UInt32   (trip B, metres → ÷1000 = km, resettable)
 *   [40..43] fwVersion      : UInt32   (packed semver (major<<16)|(minor<<8)|patch)
 *   [44]     otaState       : UInt8    (enum 0..8)
 *   [45]     otaProgress    : UInt8    (0..100)
 *   [46..47] padding        (0)
 *   [48..49] phaseA         : Int16    (deci-amps, NANJING only)
 *   [50..51] phaseC         : Int16    (deci-amps, NANJING only)
 *   [52..53] power          : UInt16   (watts, NANJING only)
 *   [54]     controllerType : UInt8    (0 VOTOL / 1 NANJING)
 *   [55]     padding        (0)
 *
 * Total message size: 8 (header) + 8 (pointer) + 56 (data) = 72 bytes. A proper
 * Cap'n Proto reader ignores unknown trailing bytes AND tolerates a *shorter*
 * payload (missing trailing fields read as 0), so [readFrom] honors the frame
 * length rather than assuming any fixed size.
 */
object TelemetrySchema {

    private const val DATA_WORDS = 7
    private const val PTR_WORDS = 0
    private const val STRUCT_DATA_SIZE = DATA_WORDS * 8 // 56 bytes
    private const val HEADER_SIZE = 8  // segment table
    private const val POINTER_SIZE = 8 // root struct pointer
    const val MESSAGE_SIZE = HEADER_SIZE + POINTER_SIZE + STRUCT_DATA_SIZE // 72 bytes

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
    // Appended fields (@15+). Absent on a shorter legacy frame → read as 0.
    private const val OFF_TRIP_A_METERS = 32
    private const val OFF_TRIP_B_METERS = 36
    private const val OFF_FW_VERSION = 40
    private const val OFF_OTA_STATE = 44
    private const val OFF_OTA_PROGRESS = 45
    private const val OFF_PHASE_A = 48
    private const val OFF_PHASE_C = 50
    private const val OFF_POWER = 52
    private const val OFF_CONTROLLER_TYPE = 54

    /**
     * Reads a [VotolTelemetry] from a Cap'n Proto serialized message. Reads only
     * the ordinals it knows and is **length-aware**: a shorter (legacy) payload
     * that predates the @15+ appends decodes cleanly with the missing trailing
     * fields read as 0; a longer payload from a newer board is also tolerated.
     */
    fun readFrom(payload: ByteArray): VotolTelemetry {
        // A frame shorter than the Cap'n Proto envelope (segment header + root
        // pointer) can't carry a struct at all — reject it so a malformed payload
        // surfaces as a decode failure (mapper → null) rather than a zeroed frame.
        // Appended-field tolerance (shorter = missing @15+ trailing fields) is
        // handled below via `dataAvail`; that's distinct from a truncated envelope.
        require(payload.size >= HEADER_SIZE + POINTER_SIZE) {
            "telemetry payload too short: ${payload.size} bytes"
        }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Skip segment table (8 bytes) + root pointer (8 bytes).
        val dataOffset = HEADER_SIZE + POINTER_SIZE

        // Bytes of data section actually present in this frame. Appended fields
        // beyond this are read as 0 (honors LEN per the forward-compat contract).
        val dataAvail = (payload.size - dataOffset).coerceAtLeast(0)
        fun i8(off: Int): Int = if (off + 1 <= dataAvail) buf.get(dataOffset + off).toInt() else 0
        fun u8(off: Int): Int = if (off + 1 <= dataAvail) buf.get(dataOffset + off).toInt() and 0xFF else 0
        fun i16(off: Int): Int = if (off + 2 <= dataAvail) buf.getShort(dataOffset + off).toInt() else 0
        fun u16(off: Int): Int = if (off + 2 <= dataAvail) buf.getShort(dataOffset + off).toInt() and 0xFFFF else 0
        fun u32(off: Int): Long = if (off + 4 <= dataAvail) buf.getInt(dataOffset + off).toLong() and 0xFFFFFFFFL else 0L

        return VotolTelemetry(
            batteryDeciVolts = u16(OFF_BATTERY_DECIVOLTS),
            batteryPercent = u8(OFF_BATTERY_PERCENT),
            batteryCurrent = i8(OFF_BATTERY_CURRENT), // signed Int8
            currentMotor = i16(OFF_CURRENT_MOTOR),
            rpm = u16(OFF_RPM),
            speedKmh = u16(OFF_SPEED_KMH),
            controllerTempC = i8(OFF_CONTROLLER_TEMP),
            motorTempC = i8(OFF_MOTOR_TEMP),
            batteryTempC = i8(OFF_BATTERY_TEMP),
            driveMode = u8(OFF_DRIVE_MODE),
            flags = u8(OFF_FLAGS),
            faultCode = u32(OFF_FAULT_CODE),
            seq = u32(OFF_SEQ),
            odoMeters = u32(OFF_ODO_METERS),
            tripMeters = u32(OFF_TRIP_METERS),
            tripAMeters = u32(OFF_TRIP_A_METERS),
            tripBMeters = u32(OFF_TRIP_B_METERS),
            fwVersion = u32(OFF_FW_VERSION),
            otaState = u8(OFF_OTA_STATE),
            otaProgress = u8(OFF_OTA_PROGRESS),
            phaseA = i16(OFF_PHASE_A),
            phaseC = i16(OFF_PHASE_C),
            power = u16(OFF_POWER),
            controllerType = u8(OFF_CONTROLLER_TYPE)
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
        val tripMeters: Long,
        // Appended fields (@15+). 0 on a legacy short frame or the STM32/UART link.
        val tripAMeters: Long = 0,
        val tripBMeters: Long = 0,
        /** Packed semver `(major<<16)|(minor<<8)|patch`; 0 = not reported. */
        val fwVersion: Long = 0,
        val otaState: Int = 0,
        val otaProgress: Int = 0,
        /** NANJING-only per-phase currents / electrical power (0 on VOTOL). */
        val phaseA: Int = 0,
        val phaseC: Int = 0,
        val power: Int = 0,
        /** 0 = VOTOL (EM-100), 1 = NANJING — the controller/motor model. */
        val controllerType: Int = 0
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
        private var tripAMeters: Int = 0
        private var tripBMeters: Int = 0
        private var fwVersion: Int = 0
        private var otaState: Byte = 0
        private var otaProgress: Byte = 0
        private var phaseA: Short = 0
        private var phaseC: Short = 0
        private var power: Short = 0
        private var controllerType: Byte = 0

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
        fun setTripAMeters(value: Int) { tripAMeters = value }
        fun setTripBMeters(value: Int) { tripBMeters = value }
        fun setFwVersion(value: Int) { fwVersion = value }
        fun setOtaState(value: Byte) { otaState = value }
        fun setOtaProgress(value: Byte) { otaProgress = value }
        fun setPhaseA(value: Short) { phaseA = value }
        fun setPhaseC(value: Short) { phaseC = value }
        fun setPower(value: Short) { power = value }
        fun setControllerType(value: Byte) { controllerType = value }

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
            buf.putInt(tripAMeters)         // [32..35]
            buf.putInt(tripBMeters)         // [36..39]
            buf.putInt(fwVersion)           // [40..43]
            buf.put(otaState)               // [44]
            buf.put(otaProgress)            // [45]
            buf.put(0)                      // [46] padding
            buf.put(0)                      // [47] padding
            buf.putShort(phaseA)            // [48..49]
            buf.putShort(phaseC)            // [50..51]
            buf.putShort(power)             // [52..53]
            buf.put(controllerType)         // [54]

            // Pad any remaining reserved bytes to zero (position should already be 56).
            while (buf.position() < dataStart + STRUCT_DATA_SIZE) {
                buf.put(0)
            }

            return buf.array()
        }
    }
}
