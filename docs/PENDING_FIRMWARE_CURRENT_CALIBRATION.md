# Pending firmware — motor-current calibration

**Status:** ⏳ Waiting on the firmware team.
**Impact (app):** **Current, Power, Wh/km, and Range display "—"** on the real
controller — on purpose — until the current channel is calibrated.

---

## 1. What's blocked and why

The wire field **`motorCurrentRaw` (@1)** is **provisional / uncalibrated**. Per the
BLE spec ([`EVDISPLAY_BLE_COMMUNICATION.md`](EVDISPLAY_BLE_COMMUNICATION.md) §5b) it is
raw counts with **scale TBD**, and reads **~0 until road-calibrated**.

Everything electrical downstream depends on it:

```
motorCurrentRaw (raw, ~0)
   └─► current (A)  ─┐
                     ├─► power = voltage × current  ─► Wh/km (efficiency) ─► Range
   voltage (OK) ─────┘
```

So with current ≈ 0: power ≈ 0, efficiency is meaningless, and range would be 0 or
infinite. Rather than show a misleading number, the app shows **"—"** for all four.

## 2. How the app gates it today

- [`TelemetryConstants.CURRENT_CHANNEL_CALIBRATED`](../app/src/main/java/com/example/displayapp/data/protocol/TelemetryConstants.kt)
  = **`false`** (real-hardware default).
- `TelemetryMapper` sets `VehicleData.currentAvailable = CURRENT_CHANNEL_CALIBRATED`.
- `DashboardViewModel` forces `efficiency.whPerKm` / `efficiency.rangeKm` to `null`
  when `currentAvailable == false`.
- The UI renders `null` / unavailable as **"—"** (Current, Power, Wh/km, Range tiles
  and the Home "Range" / "Avg. Efficiency" stats).

This is enforced by a unit test (`VotolTelemetryFrameTest`) which asserts
`currentAvailable == false` for the current wire capability.

## 3. What we need from the firmware team

To turn these values on, provide the **calibration for `motorCurrentRaw`**:

| # | Item | Value (firmware to provide) |
|---|------|------------------------------|
| 1 | **Scale** — amps per raw count (or the formula raw → A) | `____` |
| 2 | **Sign convention** — is positive = discharge and negative = regen? | `____` |
| 3 | **Offset / zero point** (if any) | `____` |
| 4 | **Units / resolution** (e.g. 0.1 A per count) | `____` |
| 5 | Is the value now **populated on the wire** (non-zero when riding)? | ☐ Yes ☐ Still 0 |
| 6 | A **golden sample**: a known current (A) ↔ raw count pair to verify | `____` |

## 4. App change once calibrated (small, localized)

1. Apply the scale/sign in `TelemetryMapper` (raw → amps), incl. the regen sign for
   the `flags` bit-7 `regen` semantics.
2. Flip `CURRENT_CHANNEL_CALIBRATED = true`.
3. Update the `VotolTelemetryFrameTest` expectations (`currentAvailable = true`, the
   decoded current value from the golden sample).

Nothing above the transport/protocol layer changes — Current, Power, Wh/km, and Range
light up automatically because they already read the same `VehicleData`.

## 5. Notes

- **Voltage / speed / rpm / battery % / temps / mode are unaffected** — they're
  already live and correct.
- The `flags` **regen** bit (bit 7) is also derived from `motorCurrentRaw`, so it stays
  0 until this calibration lands.
- Temporary demo only: setting `CURRENT_CHANNEL_CALIBRATED = true` makes the simulator's
  injected currents drive these tiles, but on the **real bike** it shows a misleading
  0 W / 0 A — keep it `false` for hardware until calibrated.

---
*Owner: firmware team · Consumer: mobile app · Ref: EVDISPLAY_BLE_COMMUNICATION.md §5b*
