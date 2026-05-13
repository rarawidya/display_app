package com.example.displayapp.domain.model

/**
 * Canonical telemetry sample shared by every layer of the app.
 *
 * `temperature` is the **motor** temp. `batteryTemperature` and
 * `controllerTemperature` are independent wire channels added in
 * telemetry.capnp v2.
 *
 * `rpm` and `power` are **derived once** in
 * [com.example.displayapp.data.protocol.TelemetryDerivations] — both the
 * live mapper (`TelemetryMapper`) and the persisted-entity decoder
 * (`TelemetryReplaySource`, `CsvExporter`, `TripDetailViewModel`) call
 * the same helpers, so Drive / Charts / Logs / Trip Detail / replay / CSV
 * all read bit-identical values. No UI layer derives them locally.
 *
 *   rpm   = speed × 100         (display proxy, NOT true motor RPM)
 *   power = voltage × current   (Watts; sign per telemetry.capnp —
 *                                positive = discharge, negative = regen)
 *
 * Rolling aggregates (Wh/km, range) are owned by [EfficiencyTracker] and
 * surfaced via the Dashboard UI state, not this per-frame model.
 *
 * `timestamp` is wall-clock at decode time (see TelemetryMapper). It is
 * NOT MCU boot-relative — `frame.timestamp` is reserved for Phase 3's
 * deterministic-replay work.
 */
data class VehicleData(
    val speed: Int = 0,
    val batteryPercent: Int = 0,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val temperature: Int = 0,
    val batteryTemperature: Int = 0,
    val controllerTemperature: Int = 0,
    val vehicleMode: VehicleMode = VehicleMode.PARK,
    val timestamp: Long = System.currentTimeMillis(),
    // Derived at decode-time — see class doc.
    val rpm: Int = 0,
    val power: Float = 0f
)

/**
 * Driving modes surfaced by the cockpit's mode selector.
 *
 * REGEN is a transient state — the controller signals it when regenerative
 * braking is actively recovering energy. It's emitted as wire value `4`;
 * see [com.example.displayapp.data.protocol.TelemetryMapper].
 */
enum class VehicleMode { PARK, ECO, NORMAL, SPORT, REGEN }
