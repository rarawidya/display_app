package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.data.persistence.entity.TelemetryEntity
import com.innodrive.evdash.domain.model.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the single canonical derivation site. A regression here would
 * silently desync Drive / Charts / Logs / Trip Detail / CSV / replay, so these
 * pin the fixed-point inverse scaling and the v6-vs-pre-v6 decode branches.
 */
class TelemetryDerivationsTest {

    private fun entity(
        speed: Int = 0,
        battery: Int = 0,
        voltage: Int = 0,
        current: Int = 0,
        temperature: Int = 0,
        batteryTemperature: Int = 0,
        controllerTemperature: Int = 0,
        mode: Int = 0,
        timestamp: Long = 0L,
        rpm: Int? = null,
        powerW: Float? = null,
    ) = TelemetryEntity(
        tripId = 1L,
        timestamp = timestamp,
        speed = speed,
        battery = battery,
        voltage = voltage,
        current = current,
        temperature = temperature,
        mode = mode,
        batteryTemperature = batteryTemperature,
        controllerTemperature = controllerTemperature,
        rpm = rpm,
        powerW = powerW,
    )

    @Test
    fun `legacy rpm derivation is speed times 100`() {
        assertEquals(3000, TelemetryDerivations.rpmFromSpeedKmh(30))
        assertEquals(0, TelemetryDerivations.rpmFromSpeedKmh(0))
    }

    @Test
    fun `power is volts times amps and keeps regen sign`() {
        assertEquals(720f, TelemetryDerivations.powerFromVoltsAmps(72f, 10f), 1e-4f)
        assertEquals(-360f, TelemetryDerivations.powerFromVoltsAmps(72f, -5f), 1e-4f)
    }

    @Test
    fun `entity fixed-point inverse scaling`() {
        assertEquals(30, TelemetryDerivations.speedKmhFromEntity(305)) // ÷10, truncated
        assertEquals(72.5f, TelemetryDerivations.voltageFromEntity(7250), 1e-4f)
        assertEquals(-10.5f, TelemetryDerivations.currentFromEntity(-1050), 1e-4f)
    }

    @Test
    fun `decodeEntity reads persisted rpm and power verbatim on v6 rows`() {
        // Persisted values must win so a future formula change never rewrites history.
        val decoded = TelemetryDerivations.decodeEntity(
            entity(speed = 305, voltage = 7200, current = 1000, rpm = 1234, powerW = 555f)
        )
        assertEquals(1234, decoded.rpm)
        assertEquals(555f, decoded.power, 0f)
    }

    @Test
    fun `decodeEntity falls back to formulas on pre-v6 rows`() {
        // rpm/powerW null → recompute from the stored fields.
        val decoded = TelemetryDerivations.decodeEntity(
            entity(speed = 300, voltage = 7200, current = 1000) // 30 km/h, 72 V, 10 A
        )
        assertEquals(30 * 100, decoded.rpm)
        assertEquals(72f * 10f, decoded.power, 1e-3f)
    }

    @Test
    fun `decodeEntity maps every SI field and clamps unknown mode to PARK`() {
        val decoded = TelemetryDerivations.decodeEntity(
            entity(
                speed = 455, battery = 80, voltage = 8090, current = -1500,
                temperature = 40, batteryTemperature = 33, controllerTemperature = 38,
                mode = 99, timestamp = 12_345L
            )
        )
        assertEquals(45, decoded.speed)
        assertEquals(80, decoded.batteryPercent)
        assertEquals(80.9f, decoded.voltage, 1e-3f)
        assertEquals(-15f, decoded.current, 1e-3f)
        assertEquals(40, decoded.temperature)
        assertEquals(33, decoded.batteryTemperature)
        assertEquals(38, decoded.controllerTemperature)
        assertEquals(12_345L, decoded.timestamp)
        assertEquals(VehicleMode.PARK, decoded.vehicleMode) // ordinal 99 out of range
    }

    @Test
    fun `decodeEntity maps a valid mode ordinal`() {
        val sport = VehicleMode.SPORT.ordinal
        val decoded = TelemetryDerivations.decodeEntity(entity(mode = sport))
        assertEquals(VehicleMode.SPORT, decoded.vehicleMode)
    }
}
