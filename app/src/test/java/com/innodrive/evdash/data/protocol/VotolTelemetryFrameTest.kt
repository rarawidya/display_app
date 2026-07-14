package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.domain.model.VehicleData
import com.innodrive.evdash.domain.model.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Decoder self-test against the frozen reference frame from docs/capnpble.md §7 —
 * the post-2026-07-07-renumber `VotolTelemetry` layout (@0..@14, 32-byte data
 * section, 48-byte payload, LEN 0x30), cross-validated with pycapnp on the board.
 *
 * Struct { batteryVolt=773, batteryPercent=64, batteryCurrent=4, currentMotor=-6,
 * rpm=2710, kmh=42, tempControl=48, tempMotor=46, tempBattery=33, driveMode=2,
 * faultCode=0, seq=12345, odoMeters=0, tripMeters=0, flags=0x11 } → this exact
 * 52-byte wire frame (CRC 0x8DB0).
 */
class VotolTelemetryFrameTest {

    // capnpble.md §7 — the exact bytes on the wire (SYNC, LEN, 48-byte payload, CRC-LE).
    private val referenceFrame = intArrayOf(
        0xAA, 0x30,
        0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
        0x05, 0x03, 0x40, 0x04, 0xFA, 0xFF, 0x96, 0x0A,
        0x2A, 0x00, 0x30, 0x2E, 0x21, 0x02, 0x11, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x39, 0x30, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xB0, 0x8D
    ).map { it.toByte() }.toByteArray()

    private val referencePayload: ByteArray = referenceFrame.copyOfRange(2, 2 + 0x30)

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
        assertEquals(773, t.batteryDeciVolts)
        assertEquals(64, t.batteryPercent)
        assertEquals(4, t.batteryCurrent)
        assertEquals(-6, t.currentMotor)
        assertEquals(2710, t.rpm)
        assertEquals(42, t.speedKmh)
        assertEquals(48, t.controllerTempC)
        assertEquals(46, t.motorTempC)
        assertEquals(33, t.batteryTempC)
        assertEquals(2, t.driveMode)
        assertEquals(0x11, t.flags)
        assertEquals(0L, t.faultCode)
        assertEquals(12345L, t.seq)
        assertEquals(0L, t.odoMeters)
        assertEquals(0L, t.tripMeters)
        // A legacy pre-@15 frame must decode with the appended fields read as 0
        // (forward-compat contract: honor LEN, missing trailing fields = 0).
        assertEquals(0L, t.tripAMeters)
        assertEquals(0L, t.tripBMeters)
        assertEquals(0L, t.fwVersion)
        assertEquals(0, t.otaState)
        assertEquals(0, t.otaProgress)
        assertEquals(0, t.controllerType)
    }

    @Test
    fun crc_matches_the_reference_over_len_plus_payload() {
        val lenAndPayload = ByteArray(1 + referencePayload.size)
        lenAndPayload[0] = referencePayload.size.toByte() // 0x30
        System.arraycopy(referencePayload, 0, lenAndPayload, 1, referencePayload.size)
        assertEquals(0x8DB0, Crc16.compute(lenAndPayload))
    }

    @Test
    fun mapper_translates_reference_frame_to_vehicle_data() {
        val vd = TelemetryMapper().map(referencePayload, VehicleData())
        assertNotNull(vd)
        vd!!
        assertEquals(42, vd.speed)                 // kmh direct
        assertEquals(2710, vd.rpm)                 // rpm from wire, not derived
        assertEquals(77.3f, vd.voltage, 0.001f)    // 773 deci-volts ÷ 10
        assertEquals(-0.6f, vd.current, 0.001f)    // currentMotor=-6 deci-amps ÷ 10
        assertEquals(46, vd.temperature)           // motor temp
        assertEquals(48, vd.controllerTemperature)
        assertEquals(33, vd.batteryTemperature)    // real tempBattery channel now
        assertEquals(64, vd.batteryPercent)
        assertEquals(4f, vd.batteryCurrent, 0.001f) // pack current, +A = charging
        assertEquals(0f, vd.odometerKm, 0.001f)
        assertEquals(0f, vd.tripKm, 0.001f)
        assertEquals(VehicleMode.NORMAL, vd.vehicleMode) // driveMode 2 = Urban → NORMAL
        assertEquals(12345L, vd.seq)
        assertEquals(0x11, vd.flags)
        assertEquals(true, vd.currentAvailable)
        assertEquals(true, vd.batteryTempAvailable) // real tempBattery @8 now
        assertEquals(true, vd.batteryKnown)         // reference batteryPercent = 64 (≠ 255)
    }

    @Test
    fun odometer_decodes_as_metres_to_km() {
        // capnpble.md §7: odo_km=456.78 → odoMeters=456780; trip_km=12.34 → tripMeters=12340.
        val payload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(773)
            setOdoMeters(456_780)
            setTripMeters(12_340)
        }
        val vd = TelemetryMapper().map(payload, VehicleData())!!
        assertEquals(456.78f, vd.odometerKm, 0.001f)
        assertEquals(12.34f, vd.tripKm, 0.001f)
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
        // Same envelope as the reference, but batteryPercent (data offset 2 →
        // payload offset 16+2) = 0xFF (not yet known).
        val payload = referencePayload.copyOf()
        payload[16 + 2] = 0xFF.toByte()
        val previous = VehicleData(batteryPercent = 73)
        val vd = TelemetryMapper().map(payload, previous)
        assertEquals(73, vd!!.batteryPercent) // holds previous
        assertEquals(false, vd.batteryKnown)  // and flags it unknown → UI shows "—"
    }

    @Test
    fun encoder_round_trips_through_decoder() {
        val payload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(773)
            setBatteryPercent(64)
            setBatteryCurrent(4)
            setCurrentMotor((-6).toShort())
            setRpm(2710)
            setSpeedKmh(42)
            setControllerTempC(48)
            setMotorTempC(46)
            setBatteryTempC(33)
            setDriveMode(2)
            setFlags(0x11)
            setSeq(12345)
            // Appended @15+ fields — round-trip through the grown 7-word data section.
            setTripAMeters(1500)
            setTripBMeters(104_500)
            setFwVersion((1 shl 16) or (2 shl 8) or 3) // 1.2.3
            setOtaState(5)
            setOtaProgress(60)
            setControllerType(1) // NANJING
        }
        val frame = FrameEncoder.encode(payload)

        // Current @0..@23 wire: 72-byte payload (LEN 0x48), 76-byte framed. The
        // official byte-golden will be regenerated by the firmware team; until then
        // we assert the envelope + a full field round-trip (encoder ⇄ decoder).
        assertEquals(0x48, frame[1].toInt() and 0xFF) // LEN = 72
        assertEquals(76, frame.size)                  // SYNC + LEN + 72 + CRC

        var decoded: TelemetrySchema.VotolTelemetry? = null
        FrameDecoder(onFrame = { decoded = TelemetrySchema.readFrom(it) }).feed(frame)
        val d = decoded!!

        assertEquals(773, d.batteryDeciVolts)
        assertEquals(12345L, d.seq)
        assertEquals(33, d.batteryTempC)
        assertEquals(1500L, d.tripAMeters)
        assertEquals(104_500L, d.tripBMeters)
        assertEquals(((1 shl 16) or (2 shl 8) or 3).toLong(), d.fwVersion)
        assertEquals(5, d.otaState)
        assertEquals(60, d.otaProgress)
        assertEquals(1, d.controllerType)
    }

    @Test
    fun mapper_surfaces_firmware_and_controller_from_extended_frame() {
        val payload = TelemetrySchema.buildMessage {
            setBatteryDeciVolts(773)
            setFwVersion(65_536) // 1.0.0
            setControllerType(1) // NANJING
            setTripAMeters(1500)
            setTripBMeters(2500)
        }
        val vd = TelemetryMapper().map(payload, VehicleData())!!
        assertEquals(65_536L, vd.firmwareVersion)
        assertEquals(1, vd.controllerType)
        assertEquals(1.5f, vd.tripAKm, 0.001f)
        assertEquals(2.5f, vd.tripBKm, 0.001f)
    }
}
