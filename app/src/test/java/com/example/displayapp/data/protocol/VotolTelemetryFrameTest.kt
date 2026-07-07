package com.example.displayapp.data.protocol

import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Decoder self-test against the frozen reference frame from the firmware team's
 * `capnp.md` §6 — "a great app-side unit test with no board required."
 *
 * Struct { batteryDeciVolts=809, batteryPercent=91, rpm=124, speedKmh=10,
 * controllerTempC=49, motorTempC=47, driveMode=3, flags=0x01, seq=12345,
 * currentMotor=0, faultCode=0 } serializes to this exact 44-byte wire frame.
 */
class VotolTelemetryFrameTest {

    // capnp.md §6 — the exact bytes on the wire (SYNC, LEN, 40-byte payload, CRC-LE).
    private val referenceFrame = intArrayOf(
        0xAA, 0x28,
        0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x29, 0x03, 0x00, 0x00, 0x7C, 0x00, 0x0A, 0x00,
        0x31, 0x2F, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00,
        0x39, 0x30, 0x00, 0x00, 0x5B, 0x00, 0x00, 0x00,
        0xE6, 0x09
    ).map { it.toByte() }.toByteArray()

    private val referencePayload: ByteArray = referenceFrame.copyOfRange(2, 2 + 0x28)

    @Test
    fun decoder_accepts_reference_frame_and_yields_expected_fields() {
        var decoded: TelemetrySchema.VotolTelemetry? = null
        var crcErrors = 0
        val decoder = FrameDecoder(
            onFrame = { decoded = TelemetrySchema.readFrom(it) },
            onCrcError = { crcErrors++ }
        )

        decoder.feed(referenceFrame)

        assertEquals("reference frame must pass CRC", 0, crcErrors)
        val t = assertNotNull("frame should decode", decoded).let { decoded!! }
        assertEquals(809, t.batteryDeciVolts)
        assertEquals(0, t.currentMotor)
        assertEquals(124, t.rpm)
        assertEquals(10, t.speedKmh)
        assertEquals(49, t.controllerTempC)
        assertEquals(47, t.motorTempC)
        assertEquals(3, t.driveMode)
        assertEquals(0x01, t.flags)
        assertEquals(0L, t.faultCode)
        assertEquals(12345L, t.seq)
        assertEquals(91, t.batteryPercent)
    }

    @Test
    fun crc_matches_the_reference_over_len_plus_payload() {
        val lenAndPayload = ByteArray(1 + referencePayload.size)
        lenAndPayload[0] = referencePayload.size.toByte() // 0x28
        System.arraycopy(referencePayload, 0, lenAndPayload, 1, referencePayload.size)
        assertEquals(0x09E6, Crc16.compute(lenAndPayload))
    }

    @Test
    fun mapper_translates_reference_frame_to_vehicle_data() {
        val vd = TelemetryMapper().map(referencePayload, VehicleData())
        assertNotNull(vd)
        vd!!
        assertEquals(10, vd.speed)                 // speedKmh direct
        assertEquals(124, vd.rpm)                  // rpm from wire, not derived
        assertEquals(80.9f, vd.voltage, 0.001f)    // 809 deci-volts ÷ 10
        assertEquals(0f, vd.current, 0.001f)       // currentMotor=0 deci-amps ÷ 10
        assertEquals(47, vd.temperature)           // motor temp
        assertEquals(49, vd.controllerTemperature)
        assertEquals(0, vd.batteryTemperature)     // not on v1 wire
        assertEquals(91, vd.batteryPercent)
        assertEquals(VehicleMode.SPORT, vd.vehicleMode) // driveMode 3
        assertEquals(12345L, vd.seq)
        assertEquals(0x01, vd.flags)
        // currentMotor is calibrated (deci-amps) → current/power available; still
        // no battery-temp channel on the v1 wire.
        assertEquals(true, vd.currentAvailable)
        assertEquals(false, vd.batteryTempAvailable)
        assertEquals(true, vd.batteryKnown) // reference batteryPercent = 91 (≠ 255)
    }

    @Test
    fun current_decodes_as_signed_deci_amps() {
        // currentMotor is signed deci-amps: 125 → 12.5 A discharge, -80 → -8.0 A regen.
        val discharge = TelemetryMapper().map(
            TelemetrySchema.buildMessage {
                setBatteryDeciVolts(809)
                setCurrentMotor(125)
            },
            VehicleData()
        )!!
        assertEquals(12.5f, discharge.current, 0.001f)
        // power = voltage × current = 80.9 × 12.5.
        assertEquals(80.9f * 12.5f, discharge.power, 0.01f)

        val regen = TelemetryMapper().map(
            TelemetrySchema.buildMessage {
                setBatteryDeciVolts(809)
                setCurrentMotor((-80).toShort())
            },
            VehicleData()
        )!!
        assertEquals(-8.0f, regen.current, 0.001f) // negative = regen
    }

    @Test
    fun battery_percent_255_holds_previous_and_marks_unknown() {
        // Same envelope as the reference, but batteryPercent = 0xFF (not yet known).
        val payload = referencePayload.copyOf()
        payload[16 + 20] = 0xFF.toByte()
        val previous = VehicleData(batteryPercent = 73)
        val vd = TelemetryMapper().map(payload, previous)
        assertEquals(73, vd!!.batteryPercent) // holds previous
        assertEquals(false, vd.batteryKnown)  // and flags it unknown → UI shows "—"
    }

    @Test
    fun encoder_round_trips_through_decoder() {
        val payload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(809)
            setRpm(124)
            setSpeedKmh(10)
            setControllerTempC(49)
            setMotorTempC(47)
            setDriveMode(3)
            setFlags(0x01)
            setSeq(12345)
            setBatteryPercent(91)
        }
        val frame = FrameEncoder.encode(payload)

        var decoded: TelemetrySchema.VotolTelemetry? = null
        FrameDecoder(onFrame = { decoded = TelemetrySchema.readFrom(it) }).feed(frame)

        assertEquals(809, decoded!!.batteryDeciVolts)
        assertEquals(12345L, decoded!!.seq)
        // Our encoder must produce the byte-identical canonical frame.
        assertEquals(referenceFrame.toList(), frame.toList())
    }
}
