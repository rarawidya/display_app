package com.innodrive.evdash.domain.model

/**
 * Canonical telemetry sample shared by every layer of the app.
 *
 * `temperature` is the **motor** temp. `batteryTemperature` and
 * `controllerTemperature` are independent wire channels added in
 * telemetry.capnp v2.
 *
 * `rpm` is a **real wire field** (`VotolTelemetry.rpm`) read straight off the
 * frame by `TelemetryMapper`; the legacy speed×100 proxy survives only as the
 * `TelemetryDerivations.decodeEntity` fallback for pre-v6 persisted rows.
 * `power` is **derived once** in
 * [com.innodrive.evdash.data.protocol.TelemetryDerivations] — both the live
 * mapper and the persisted-entity decoder (`TelemetryReplaySource`,
 * `CsvExporter`, `TripDetailViewModel`) call the same helper, so Drive /
 * Charts / Logs / Trip Detail / replay / CSV all read bit-identical values.
 * No UI layer derives power locally.
 *
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
    val power: Float = 0f,
    // ── Odometer + pack current (VotolTelemetry @2/@12/@13, capnpble.md §3/§5) ──
    /** Battery **pack** current in amps, signed; positive = charging into the pack. */
    val batteryCurrent: Float = 0f,
    /** Lifetime odometer in km (`odoMeters/1000`); board-integrated, persists across reboots. */
    val odometerKm: Float = 0f,
    /** Resettable trip odometer in km (`tripMeters @13/1000`; legacy alias of trip A). */
    val tripKm: Float = 0f,
    /** Trip meter A in km (`tripAMeters @15/1000`); resettable via ODO_RESET_TRIP_A. */
    val tripAKm: Float = 0f,
    /** Trip meter B in km (`tripBMeters @16/1000`); independently resettable. */
    val tripBKm: Float = 0f,
    // ── Board firmware + OTA (VotolTelemetry @17..@19; board→app TX-only) ─────────
    /**
     * Board firmware as packed semver `(major<<16)|(minor<<8)|patch` (`fwVersion @17`);
     * 0 = not reported (STM32/UART link, or a board that predates the field).
     * Decode for display with [firmwareVersionName].
     */
    val firmwareVersion: Long = 0,
    /** OTA state enum 0..8 (`otaState @18`): 0 IDLE … 7 SUCCESS, 8 FAILED. */
    val otaState: Int = 0,
    /** OTA download/apply progress 0..100 (`otaProgress @19`). */
    val otaProgress: Int = 0,
    // ── Controller identity (VotolTelemetry @23; board-sourced) ──────────────────
    /** Controller/motor model: 0 = VOTOL (EM-100), 1 = NANJING (`controllerType @23`). */
    val controllerType: Int = 0,
    // ── VotolTelemetry wire fields (capnp.md §3) ─────────────────────────────
    // Carried live from the frame and persisted since schema v7, so decodeEntity
    // restores them for replayed samples (pre-v7 rows read 0).
    /** Controller fault bitfield (`@7 faultCode`); 0 = no fault. */
    val faultCode: Long = 0,
    /** Status bitfield (`@8 flags`): bit0 engineRunning, bit1 brake, bit2 moving, bit3 reverse. */
    val flags: Int = 0,
    /** Rolling frame counter (`@9 seq`) for drop detection. */
    val seq: Long = 0,
    // ── Field availability (so the UI shows "—" instead of misleading zeros) ──
    // Default true: persisted rows decoded via TelemetryDerivations.decodeEntity
    // keep showing their recorded values. The live mapper sets these per the
    // wire protocol's current capabilities.
    /** False when the current channel is uncalibrated (→ current & power unknown). */
    val currentAvailable: Boolean = true,
    /** False when battery-pack temperature is absent from the wire. */
    val batteryTempAvailable: Boolean = true,
    /** False when SoC is not yet known (wire `batteryPercent == 255`). */
    val batteryKnown: Boolean = true
)

/**
 * Decodes a packed-semver [VehicleData.firmwareVersion] into a `"major.minor.patch"`
 * string (e.g. `65536` → `"1.0.0"`), or null when the board doesn't report it (0).
 * This is the authoritative running-firmware version from `fwVersion @17`; prefer
 * it over the Device-Information-Service revision string.
 */
fun firmwareVersionName(packed: Long): String? {
    if (packed <= 0L) return null
    val major = (packed shr 16) and 0xFF
    val minor = (packed shr 8) and 0xFF
    val patch = packed and 0xFF
    return "$major.$minor.$patch"
}

/**
 * Human label for [VehicleData.controllerType] (`controllerType @23`):
 * 0 → "VOTOL EM-100", 1 → "NANJING". null for an unknown/future code so the UI
 * can fall back to the Device-Information-Service model number.
 */
fun controllerTypeName(code: Int): String? = when (code) {
    0 -> "VOTOL EM-100"
    1 -> "NANJING"
    else -> null
}

/**
 * Driving modes surfaced by the cockpit's mode selector.
 *
 * REGEN is a transient state — the controller signals it when regenerative
 * braking is actively recovering energy. It's emitted as wire value `4`;
 * see [com.innodrive.evdash.data.protocol.TelemetryMapper].
 */
enum class VehicleMode { PARK, ECO, NORMAL, SPORT, REGEN }
