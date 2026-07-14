package com.innodrive.evdash.data.protocol

import com.innodrive.evdash.data.persistence.entity.TelemetryEntity
import com.innodrive.evdash.domain.model.VehicleData
import com.innodrive.evdash.domain.model.VehicleMode

/**
 * Single source of truth for *derived* telemetry fields and for the
 * persisted-entity → SI decode pattern.
 *
 * Architectural rule (Phase 1 audit):
 *   Every metric displayed on Drive / Charts / Logs / Trip Detail / CSV /
 *   replay must come from one canonical derivation. No screen, ViewModel, or
 *   exporter may re-implement these formulas locally.
 *
 * ── Wire vs. persistence scaling (read this before touching the helpers) ──
 * There are TWO integer encodings and they are deliberately independent:
 *
 *   • WIRE (board → phone): `VotolTelemetry` already carries SI-adjacent
 *     fields. rpm and speedKmh are DIRECT integers; voltage is deci-volts;
 *     current is deci-amps (÷10 = A). The wire decode lives in [TelemetryMapper], which
 *     reads those fields straight off the frame — it does NOT go through the
 *     entity helpers below.
 *
 *   • PERSISTENCE (Room): [com.innodrive.evdash.data.persistence.TelemetryLogger]
 *     re-encodes a decoded [VehicleData] into `TelemetryEntity` with its own
 *     fixed-point scaling (speed×10, voltage×100, current×100). [decodeEntity]
 *     inverts exactly that scaling. This encoding is a storage detail and is
 *     intentionally decoupled from the wire, so a wire-format revision never
 *     forces a Room migration.
 *
 * Semantics — see also CLAUDE.md and `telemetry.capnp`:
 *
 *   rpm:    now a REAL wire field (`VotolTelemetry.rpm`). It is no longer
 *           derived as speed×100 for live frames. [rpmFromSpeedKmh] survives
 *           ONLY as the legacy fallback for pre-v6 persisted rows that stored
 *           no rpm column.
 *
 *   power:  instantaneous bus power in Watts, `voltage × current`. The board's
 *           `currentMotor` channel is now calibrated (signed deci-amps), so
 *           power is live and signed (negative under regen).
 */
object TelemetryDerivations {

    /**
     * Legacy RPM derivation. Kept only for the [decodeEntity] fallback on
     * pre-v6 rows recorded before rpm was persisted (and before rpm was a wire
     * field). Live frames read [VotolTelemetry.rpm] directly.
     */
    fun rpmFromSpeedKmh(speedKmh: Int): Int = speedKmh * 100

    /**
     * Canonical instantaneous power derivation. Inputs are already in SI
     * (volts, amps); output is watts with the same float-rounding everywhere.
     */
    fun powerFromVoltsAmps(voltageV: Float, currentA: Float): Float =
        voltageV * currentA

    /* ---- Persisted-entity fixed-point → SI (inverse of TelemetryLogger) ---- */

    fun speedKmhFromEntity(speedInt10: Int): Int = speedInt10 / 10
    fun voltageFromEntity(voltageInt100: Int): Float = voltageInt100 / 100f
    fun currentFromEntity(currentInt100: Int): Float = currentInt100 / 100f

    /**
     * Decode a persisted [TelemetryEntity] back into the same [VehicleData]
     * shape the live mapper would emit for these values.
     *
     * This is the bridge that keeps replay, CSV, and Trip Detail aligned with
     * the live pipeline — all of them should call this rather than touching
     * entity columns directly.
     *
     * Schema v6+ rows carry persisted [TelemetryEntity.rpm] /
     * [TelemetryEntity.powerW]; we read those verbatim so a future change to
     * the derivation formula does not retroactively alter recorded trips.
     * Pre-v6 rows have NULL in both columns — we fall back to recomputing from
     * the stored fields, preserving the historical chart shape.
     */
    fun decodeEntity(entity: TelemetryEntity): VehicleData {
        val speedKmh = speedKmhFromEntity(entity.speed)
        val voltageV = voltageFromEntity(entity.voltage)
        val currentA = currentFromEntity(entity.current)
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
            rpm = entity.rpm ?: rpmFromSpeedKmh(speedKmh),
            power = entity.powerW ?: powerFromVoltsAmps(voltageV, currentA),
            // v7 wire status fields; pre-v7 rows carry 0 (no fault / no flags).
            faultCode = entity.faultCode,
            flags = entity.flags
        )
    }
}
