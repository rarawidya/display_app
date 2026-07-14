package com.innodrive.evdash.data.fault

import com.innodrive.evdash.domain.model.VehicleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [FaultDetector] edge/hysteresis logic.
 *
 * The detector is the choke point for turning the ~10 Hz telemetry stream into
 * discrete fault rows, so these lock down that it fires on the *rising* edge
 * only, re-arms after recovery, and never flaps on a threshold.
 */
class FaultDetectorTest {

    private val detector = FaultDetector()

    private fun sample(
        motor: Int = 25,
        battery: Int = 25,
        controller: Int = 25,
        flags: Int = 0,
        faultCode: Long = 0,
        batteryPercent: Int = 80,
        batteryTempAvailable: Boolean = true,
    ) = VehicleData(
        temperature = motor,
        batteryTemperature = battery,
        controllerTemperature = controller,
        flags = flags,
        faultCode = faultCode,
        batteryPercent = batteryPercent,
        batteryTempAvailable = batteryTempAvailable,
    )

    @Test
    fun `quiet frame emits nothing`() {
        assertTrue(detector.onSample(sample()).isEmpty())
    }

    @Test
    fun `motor over-temp fires once on the rising edge`() {
        assertTrue(detector.onSample(sample(motor = 40)).isEmpty())
        val first = detector.onSample(sample(motor = 55))
        assertEquals(1, first.size)
        assertEquals(FaultDetector.TYPE_MOTOR_TEMP, first[0].type)
        assertEquals(FaultDetector.SEVERITY_WARNING, first[0].severity)
        // Still hot on the next frame → no duplicate.
        assertTrue(detector.onSample(sample(motor = 56)).isEmpty())
    }

    @Test
    fun `warning escalates to critical then does not re-fire`() {
        detector.onSample(sample(motor = 55)) // warning
        val crit = detector.onSample(sample(motor = 75))
        assertEquals(1, crit.size)
        assertEquals(FaultDetector.SEVERITY_CRITICAL, crit[0].severity)
        assertTrue(detector.onSample(sample(motor = 78)).isEmpty())
    }

    @Test
    fun `hysteresis prevents flapping at the boundary`() {
        detector.onSample(sample(motor = 55)) // arm warning
        // Dip to exactly the boundary and back up — within the clear margin,
        // so it must NOT re-fire.
        assertTrue(detector.onSample(sample(motor = 50)).isEmpty())
        assertTrue(detector.onSample(sample(motor = 55)).isEmpty())
    }

    @Test
    fun `re-arms after recovering past the clear margin`() {
        detector.onSample(sample(motor = 55)) // fire warning
        // Drop well below warn - margin (44 < 50-5) → disarm.
        assertTrue(detector.onSample(sample(motor = 40)).isEmpty())
        // Rise again → fires anew.
        val again = detector.onSample(sample(motor = 55))
        assertEquals(1, again.size)
        assertEquals(FaultDetector.TYPE_MOTOR_TEMP, again[0].type)
    }

    @Test
    fun `controller fault fires once and reports hex code`() {
        val f = detector.onSample(sample(faultCode = 0x2A))
        assertEquals(1, f.size)
        assertEquals(FaultDetector.TYPE_CONTROLLER_FAULT, f[0].type)
        assertEquals(FaultDetector.SEVERITY_CRITICAL, f[0].severity)
        assertTrue(f[0].message.contains("0x2A"))
        assertTrue(detector.onSample(sample(faultCode = 0x2A)).isEmpty())
        // Clears then re-fires on a new fault.
        assertTrue(detector.onSample(sample(faultCode = 0)).isEmpty())
        assertEquals(1, detector.onSample(sample(faultCode = 0x2A)).size)
    }

    @Test
    fun `low battery flag fires once`() {
        val f = detector.onSample(sample(flags = FaultDetector.FLAG_LOW_BATTERY, batteryPercent = 12))
        assertEquals(1, f.size)
        assertEquals(FaultDetector.TYPE_BATT_LOW, f[0].type)
        assertTrue(f[0].message.contains("12%"))
        assertTrue(detector.onSample(sample(flags = FaultDetector.FLAG_LOW_BATTERY)).isEmpty())
    }

    @Test
    fun `battery temp ignored when channel unavailable`() {
        assertTrue(
            detector.onSample(sample(battery = 90, batteryTempAvailable = false)).isEmpty()
        )
    }

    @Test
    fun `independent channels fire independently in one frame`() {
        val f = detector.onSample(sample(motor = 75, controller = 55, faultCode = 1))
        // controller-fault, motor crit, controller warn.
        assertEquals(3, f.size)
        assertEquals(FaultDetector.TYPE_CONTROLLER_FAULT, f[0].type)
        assertTrue(f.any { it.type == FaultDetector.TYPE_MOTOR_TEMP && it.severity == FaultDetector.SEVERITY_CRITICAL })
        assertTrue(f.any { it.type == FaultDetector.TYPE_CONTROLLER_TEMP && it.severity == FaultDetector.SEVERITY_WARNING })
    }

    @Test
    fun `reset re-arms all latches`() {
        detector.onSample(sample(motor = 75))
        detector.reset()
        val f = detector.onSample(sample(motor = 75))
        assertEquals(1, f.size)
    }
}
