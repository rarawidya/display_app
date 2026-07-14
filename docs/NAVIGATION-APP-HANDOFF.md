# EVdisplay Navigation — App→Firmware Handoff (what the phone now sends)

**Date:** 2026-07-08 · **From:** Android app team · **To:** firmware (EVdisplay board)
**Companion specs:** [`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md) (the wire
contract — unchanged, ordinals frozen) · [`README-MAPS-HANDOFF.md`](README-MAPS-HANDOFF.md)
(the architecture decision).

This documents the app build that closes **every item in the ⭐ ACTION REQUIRED
block** from your 2026-07-08 evening live test. Nothing on the wire changed —
same frames, same `0xAF06`, same CRC — only *when* frames are sent and *what the
text/counter fields carry*. Use this to re-verify and to tune board-side rendering.

---

## 1. Status vs. your live-test findings

| Your finding (live test 2026-07-08) | Status in this build |
|---|---|
| One burst at route creation, then **zero writes for 60 s** → NAV OFFLINE | **Fixed** — dedicated heartbeat ticker, §2. `/var/run/nav_state` mtime now ticks continuously |
| `streetName` carried the maneuver text ("Turn right - Turn right" on screen) | **Fixed** — carries only the road you turn **onto**; blank for unnamed roads (§3) |
| `destinationName` empty → bottom card title "--" | **Fixed** — carries the picked place's label (§3) |
| `seq` stuck at `0` (only bumped on the cancel frame) | **Fixed** — +1 on **every** NavInstruction frame, including heartbeats and the cancel frame |
| RouteChunk (29 pts) decoded & rendered | **Unchanged** — same encoder, same parameters (§4) |

---

## 2. Send cadence (what arrives when)

The heartbeat is driven by the app's own 250 ms ticker, **independent of GPS
fixes** — a stationary phone with no location updates still streams.

| Event | Frame(s) | Write mode | Timing |
|---|---|---|---|
| Route confirmed (session start) | `RouteSummary` → all `RouteChunk`s → first `NavInstruction` | With-Response (summary/chunks), then WWR | immediately |
| Maneuver change / state change | `NavInstruction` | WWR | immediately |
| Moving (`speedKmh ≥ 2`) | `NavInstruction` heartbeat (full state, repeated last snapshot) | WWR | **1 Hz** |
| Stationary (`speedKmh < 2`) | `NavInstruction` heartbeat | WWR | **0.2 Hz** (every 5 s) |
| Reroute (3 consecutive off-route fixes) | `state=rerouting` instruction(s), then **new `routeId`**: `RouteSummary` → `RouteChunk`s → instruction | mixed as above | immediately on replan |
| Arrival | `state=arrived, distanceRemainingM=0` | **With-Response** | immediately, then session ends |
| User cancel | `state=cancelled` | **With-Response** | immediately, then session ends |
| **BLE reconnect mid-route** | cached `RouteSummary` → `RouteChunk`s → **latest `NavInstruction` immediately** | With-Response, then WWR | on CONNECTED |

Board-side expectations that follow:

- **Moving:** `nav_state` mtime ticks ≤1 s → stays LIVE continuously.
- **Stationary:** frames every 5 s — the board's <5 s "fresh" window means the
  display may sit right at the LIVE/dim boundary between beats. That is the
  spec'd 0.2 Hz cadence; if the dim flicker looks bad on the bench, widen the
  fresh window slightly (e.g. 6 s) on your side — the app won't send faster
  while stationary.
- Heartbeat frames are **repeats of the last snapshot** (full state, not
  deltas): distances do *not* decrease between GPS fixes. Keep interpolating
  from telemetry speed and snapping on each frame, as §7.4 of the spec already
  describes.
- `seq` increments on every instruction frame and wraps at 16 bits. Frames
  within one route now arrive strictly ordered; your stale-guard can stay as-is.

---

## 3. Field semantics (text + counters)

| Field | What the app sends now |
|---|---|
| `NavInstruction.streetName` | GraphHopper `street_name` of the **upcoming** maneuver = the road you turn onto (e.g. `"Jl. Sudirman"`). **Blank when the road is unnamed** — render the maneuver arrow/enum text alone in that case, there is no more "Turn right - Turn right" duplication. ≤40 UTF-8 B, truncated on a codepoint boundary. |
| `RouteSummary.destinationName` | The place label the rider picked (search result name, or the reverse-geocoded address of a dropped pin). Snapshotted at route confirm. Worst case it is `"Dropped pin"` (reverse geocode hadn't resolved) — still non-empty. ≤48 B. |
| `NavInstruction.seq` | +1 per frame sent (heartbeats included), starts at 0 per session, wraps at 65535. |
| `routeId` | Bumps on every (re)plan. Unchanged: it is your "discard and re-learn" signal, and chunks/summary always precede instructions for a new id. |
| `speedLimitKmh` | **Always 0 (unknown)** — the GraphHopper Directions response we use does not include per-segment speed limits. Keep the field hidden when 0. |
| `roundaboutExit` | GraphHopper `exit_number` (0 = n/a), paired with `maneuver=roundabout`. |
| `etaSeconds` | Route duration scaled linearly by distance remaining — coarse but monotonic; snaps on reroute. |
| Cancel frame | `state=cancelled` with the last route's `routeId`, `maneuver=none`, distances 0. |

---

## 4. RouteChunk parameters (unchanged — for reference)

- Polyline downsampled to **≤200 points**, split into windows of **40 points**
  (well under your 45-point/240 B ceiling), consecutive windows **overlap by one
  vertex** so concatenation by `index` leaves no gaps.
- Deltas are 1e-5 deg Int16 from the running position; anchor is absolute 1e-7 deg.
- All chunks are Write-With-Response, sent after the `RouteSummary` whose
  `polylineChunks` carries the real count, and re-sent (same encoder path) on
  reroute with the bumped `routeId` and on BLE reconnect.
- Longest realistic route at 200 points → 6 chunks ≈ 1.4 KB total, still a
  sub-second burst.

---

## 5. How to re-verify (same drill as your last test)

1. Phone connected, pick a destination, confirm.
2. `tail -f /var/log/ble-gatt.log` → expect `RouteSummary` + chunks + first
   instruction burst, then **a steady instruction line every ~1 s** (or 5 s if
   the phone is stationary on the bench — walk it around or fake movement to see 1 Hz).
3. `ls -l --full-time /var/run/nav_state` → mtime ticks at that rate; maps
   screen LIVE within 1 s and **stays** live past 15 s.
4. `cat /var/run/nav_state` → `street` = a road name or empty (never "Turn
   right"), `dest` = the picked place, `seq` climbing by 1.
5. Kill/restore the BLE link mid-route → summary + chunks + an instruction
   arrive immediately on reconnect, without waiting for the next heartbeat.
6. Cancel on the phone → one reliable `state=cancelled` frame; the stream stops
   (nothing after it except silence — the session is closed, no stray heartbeat).

---

## 6. App-side source of truth (for cross-reading, not required)

| Behavior | File |
|---|---|
| Cadence, seq, reconnect re-seed, cancel | `app/src/main/java/com/innodrive/evdash/data/navigation/RouteNavigator.kt` |
| `streetName` extraction (`street_name` only) | `app/src/main/java/com/innodrive/evdash/data/navigation/graphhopper/GraphHopperRoutePlanner.kt` |
| `destinationName` hand-off (UI → session) | `presentation/viewmodel/MapsViewModel.kt` → `data/navigation/NavigationCoordinator.kt` |
| Wire encoders (byte-exact vs §9 goldens, unchanged) | `app/src/main/java/com/innodrive/evdash/data/protocol/NavigationSchema.kt` + `NavigationFrameTest` |

Anything that looks off on the bench: grab the `DOWNLINK raw len=… hex=…` line
from `/var/log/ble-gatt.log` and send it over — the app team will decode it
byte-by-byte against the golden frames.

---
*Owner: mobile team · Wire contract unchanged (`navigation.capnp` ordinals frozen) ·
Questions → app team.*
