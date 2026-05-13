package com.example.displayapp.data.protocol

import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.model.VehicleMode

/**
 * Single source of truth for *derived* telemetry fields (rpm, power) and for
 * the wire-int → SI-unit decode pattern.
 *
 * Architectural rule (Phase 1 audit):
 *   Every metric displayed on Drive / Charts / Logs / Trip Detail / CSV /
 *   replay must come from one canonical derivation. No screen, ViewModel, or
 *   exporter may re-implement these formulas locally.
 *
 * To add a new derived field (e.g. SoC fraction, motor torque):
 *   1. Add the pure formula here as a top-level function with documented
 *      units in / units out.
 *   2. Call it from [TelemetryMapper.map] (live decode) and from
 *      [decodeEntity] (replay/CSV/Trip Detail decode).
 *   3. Never re-derive at the consumer site — read `VehicleData.theField`.
 *
 * Semantics — see also CLAUDE.md and `telemetry.capnp`:
 *
 *   rpm:    a display-layer rotational proxy, `speedKmh × 100`. Unit-agnostic;
 *           it does NOT represent true motor electrical frequency. If a
 *           future thermal / torque model needs real RPM, add it as a
 *           separate wire channel rather than overloading this one.
 *
 *   power:  instantaneous bus power in Watts, `voltage × current`. Sign
 *           convention: positive = discharge (drawing from pack),
 *           negative = regen (returning to pack). The wire-level sign comes
 *           from the controller's signed Int16 current channel — see
 *           `TelemetryFrame.current` in telemetry.capnp.
 */
object TelemetryDerivations {

    /**
     * Canonical RPM derivation. Input is **integer km/h** (matches
     * [VehicleData.speed]); output is the same display-proxy used by every
     * surface.
     */
    fun rpmFromSpeedKmh(speedKmh: Int): Int = speedKmh * 100

    /**
     * Canonical instantaneous power derivation. Inputs are already in SI
     * (volts, amps); output is watts with the same float-rounding everywhere.
     *
     * Don't pre-multiply the wire integers and divide afterwards — call this
     * with the SI floats you already decoded so power on Drive (live) matches
     * power in Trip Detail (replay) bit-for-bit.
     */
    fun powerFromVoltsAmps(voltageV: Float, currentA: Float): Float =
        voltageV * currentA

    /**
     * Wire-int → SI conversions. Centralized so a future schema rev (e.g.
     * voltage scaled to ×1000 for 1mV precision) updates one place.
     */
    fun speedKmhFromWire(speedInt10: Int): Int = speedInt10 / 10
    fun voltageFromWire(voltageInt100: Int): Float = voltageInt100 / 100f
    fun currentFromWire(currentInt100: Int): Float = currentInt100 / 100f

    /**
     * Decode a persisted [TelemetryEntity] back into the same [VehicleData]
     * shape the live mapper would emit for these wire values.
     *
     * This is the bridge that keeps replay, CSV, and Trip Detail aligned with
     * the live pipeline — all of them should call this rather than touching
     * entity columns directly.
     */
    fun decodeEntity(entity: TelemetryEntity): VehicleData {
        val speedKmh = speedKmhFromWire(entity.speed)
        val voltageV = voltageFromWire(entity.voltage)
        val currentA = currentFromWire(entity.current)
        return VehicleData(
            speed = speedKmh,
            batteryPercent = entity.battery,
            voltage = voltageV,
            current = currentA,
            temperature = entity.temperature,
            batteryTemperature = entity.batteryTemperature,
            controllerTemperature = entity.controllerTemperature,
            vehicleMode = VehicleMode.entries.getOrElse(entity.mode) { VehicleMode.PARK },
            timestamp = entity.timestamp,
            rpm = rpmFromSpeedKmh(speedKmh),
            power = powerFromVoltsAmps(voltageV, currentA)
        )
    }
}
