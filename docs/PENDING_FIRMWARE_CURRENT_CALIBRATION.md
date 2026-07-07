# Motor-current calibration — ✅ RESOLVED

**Status:** ✅ **Calibrated & landed** (firmware capnp.md revision, 2026-07).
**Impact (app):** **Current, Power, Wh/km, and Range are now live** on the real
controller. The "—" fallback remains one flag-flip away if a future wire
revision de-calibrates the channel.

---

## 1. What was blocked (history)

The wire field **`motorCurrentRaw` (@1)** used to be provisional / uncalibrated —
raw counts with scale TBD, reading ~0 until road-calibrated. Everything electrical
downstream depended on it:

```
currentMotor (deci-amps)
   └─► current (A) = ÷10  ─┐
                           ├─► power = voltage × current  ─► Wh/km ─► Range
   voltage (OK) ───────────┘
```

While uncalibrated the app showed **"—"** for all four rather than a misleading 0.

## 2. Calibration delivered by firmware

The channel — now named **`currentMotor` (@1, Int16)** in the canonical schema —
is **CONFIRMED** (matched against the vendor display):

| # | Item | Value |
|---|------|-------|
| 1 | **Scale** — amps per count | `÷10` (deci-amps → amps) |
| 2 | **Sign convention** | positive = discharge, **negative = regen** |
| 3 | **Offset / zero point** | ~−4 counts (~0.4 A) idle offset — left unsubtracted |
| 4 | **Units / resolution** | 0.1 A per count |
| 5 | **Populated on the wire?** | ☑ Yes (non-zero when riding) |
| 6 | **Golden sample** | `125 → 12.5 A`, `−80 → −8.0 A` (unit-tested) |

## 3. App change (landed)

1. `TelemetryMapper`: `currentA = frame.currentMotor / 10f` (signed).
2. `TelemetryConstants.CURRENT_CHANNEL_CALIBRATED = true`.
3. Wire/decoder field renamed `motorCurrentRaw` → `currentMotor` (schema +
   `TelemetrySchema` + simulator, which now emits deci-amps = `amps × 10`).
4. `VotolTelemetryFrameTest`: `currentAvailable == true` + a new
   `current_decodes_as_signed_deci_amps` test for the golden sample.
5. UI: Current & Power tiles added to the Drive grid; Current + Power are now
   selectable Chart series and rendered on Trip Detail; Home "Range" /
   "Avg. Efficiency" stats light up automatically (same `VehicleData`).

## 4. Notes

- **Voltage / speed / rpm / battery % / temps / mode were always unaffected.**
- The `flags` **regen** bit (bit 7) tracks negative `currentMotor` and is now
  meaningful (threshold still wants a road ride to finalize).
- Trips recorded **before** this change persisted raw-count current, so their
  Current/Power replay series reflect that historical (unscaled) data — the
  persistence encoding is deliberately decoupled from the wire, so old rows are
  not retroactively rewritten.

---
*Owner: firmware team · Consumer: mobile app · Ref: EVDISPLAY_BLE_COMMUNICATION.md §5b · capnp.md §3*
