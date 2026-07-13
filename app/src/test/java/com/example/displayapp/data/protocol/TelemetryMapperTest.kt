package com.example.displayapp.data.protocol

import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the wire → [VehicleData] boundary. Builds real Cap'n Proto
 * payloads via [TelemetrySchema] and asserts the deci-volt / deci-amp scaling,
 * mode mapping, odometer ÷1000, the battery-unknown hold, and null on garbage.
 */
class TelemetryMapperTest {

    private val mapper = TelemetryMapper()

    /** Build a wire payload with sensible defaults, overridable per field. */
    private fun payload(
        batteryDeciVolts: Short = 809,
        batteryPercent: Byte = 80,
        batteryCurrent: Byte = 5,
        currentMotor: Short = 105,
        rpm: Short = 3000,
        speedKmh: Short = 45,
        controllerTempC: Byte = 38,
        motorTempC: Byte = 40,
        batteryTempC: Byte = 33,
        driveMode: Byte = 2,
        flags: Byte = 0x05,
        faultCode: Int = 0,
        seq: Int = 999,
        odoMeters: Int = 123_456,
        tripMeters: Int = 1000,
    ): ByteArray = TelemetrySchema.buildMessage {
        setBatteryDeciVolts(batteryDeciVolts)
        setBatteryPercent(batteryPercent)
        setBatteryCurrent(batteryCurrent)
        setCurrentMotor(currentMotor)
        setRpm(rpm)
        setSpeedKmh(speedKmh)
        setControllerTempC(controllerTempC)
        setMotorTempC(motorTempC)
        setBatteryTempC(batteryTempC)
        setDriveMode(driveMode)
        setFlags(flags)
        setFaultCode(faultCode)
        setSeq(seq)
        setOdoMeters(odoMeters)
        setTripMeters(tripMeters)
    }

    @Test
    fun `maps a full frame with correct scaling`() {
        val d = mapper.map(payload(), VehicleData())!!
        assertEquals(45, d.speed)
        assertEquals(3000, d.rpm)
        assertEquals(80, d.batteryPercent)
        assertEquals(80.9f, d.voltage, 1e-3f)   // deci-volts ÷10
        assertEquals(10.5f, d.current, 1e-3f)   // deci-amps ÷10
        assertEquals(80.9f * 10.5f, d.power, 1e-2f) // derived V×I
        assertEquals(40, d.temperature)
        assertEquals(33, d.batteryTemperature)
        assertEquals(38, d.controllerTemperature)
        assertEquals(VehicleMode.NORMAL, d.vehicleMode) // driveMode 2
        assertEquals(5f, d.batteryCurrent, 0f)
        assertEquals(123.456f, d.odometerKm, 1e-3f) // metres ÷1000
        assertEquals(1.0f, d.tripKm, 1e-3f)
        assertEquals(0x05, d.flags)
        assertEquals(999L, d.seq)
        assertTrue(d.batteryKnown)
        assertTrue(d.batteryTempAvailable)
    }

    @Test
    fun `battery percent 255 holds the previous known value`() {
        val previous = VehicleData(batteryPercent = 64)
        val d = mapper.map(payload(batteryPercent = 255.toByte()), previous)!!
        assertEquals(64, d.batteryPercent) // held, not 0/255
        assertFalse(d.batteryKnown)
    }

    @Test
    fun `negative motor current decodes as regen and negative power`() {
        val d = mapper.map(payload(currentMotor = (-150).toShort()), VehicleData())!!
        assertEquals(-15f, d.current, 1e-3f)
        assertTrue(d.power < 0f)
    }

    @Test
    fun `signed battery current`() {
        val d = mapper.map(payload(batteryCurrent = (-7).toByte()), VehicleData())!!
        assertEquals(-7f, d.batteryCurrent, 0f)
    }

    @Test
    fun `drive mode mapping`() {
        assertEquals(VehicleMode.ECO, mapper.map(payload(driveMode = 1), VehicleData())!!.vehicleMode)
        assertEquals(VehicleMode.NORMAL, mapper.map(payload(driveMode = 2), VehicleData())!!.vehicleMode)
        assertEquals(VehicleMode.SPORT, mapper.map(payload(driveMode = 3), VehicleData())!!.vehicleMode)
        assertEquals(VehicleMode.PARK, mapper.map(payload(driveMode = 0), VehicleData())!!.vehicleMode)
        assertEquals(VehicleMode.PARK, mapper.map(payload(driveMode = 9), VehicleData())!!.vehicleMode)
    }

    @Test
    fun `availability flags follow the wire capabilities`() {
        val d = mapper.map(payload(), VehicleData())!!
        assertEquals(TelemetryConstants.CURRENT_CHANNEL_CALIBRATED, d.currentAvailable)
        assertTrue(d.batteryTempAvailable)
    }

    @Test
    fun `malformed payload returns null instead of throwing`() {
        assertNull(mapper.map(byteArrayOf(0x00, 0x01, 0x02), VehicleData()))
    }
}
