# EVdisplay — Phone→Board Navigation Integration Spec (v1)

**Audience:** phone-app + firmware developers implementing turn-by-turn navigation
push to the controller display.

**Status:** phone side **implemented** (schema, encoder, transport, orchestrator,
golden-frame tests). Firmware side **implemented and deployed** (§7; server decodes
`0xAF06` → publishes `/var/run/nav_state`; the board's maps screen renders the
maneuver banner, distance/ETA card, and a live position marker on a stylized map
with touch zoom — board-local rendering, no protocol impact). **Live end-to-end with
the real app confirmed 2026-07-08:** the board received and correctly rendered the
app's `RouteSummary`, a `NavInstruction`, and a 29-point `RouteChunk` (real route
polyline + destination pin + live position marker drawn from the app's geometry).
Remaining gaps are all app-side and are exactly the action block below (heartbeat
cadence, `streetName`, `destinationName`). Phones must clear their GATT cache after
firmware updates to see `0xAF06`; every received write is hex-dumped to
`/var/log/ble-gatt.log` on the board for instant verification. The routing/map SDK is
deliberately
**not chosen** — this transport is **provider-independent**: any SDK (Mapbox Nav,
HERE, TomTom, Valhalla/GraphHopper/openrouteservice) plugs in behind one adapter
interface (§4), and nothing below the adapter depends on the provider.

**Relationship to existing channels:**
- `telemetry.capnp` (`VotolTelemetry`, `0xAF08` NOTIFY) — **frozen board→phone**
  uplink. **Untouched.**
- `PhoneNotification` (`0xAF07` WRITE, [`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md))
  — existing phone→board path. **Untouched.** Navigation is the same direction but a
  **separate message family on its own characteristic `0xAF06`** (§8).

The app computes the route (phone GPS + routing SDK) and **streams compact
instructions** to the board; the board renders the current maneuver. Navigation logic
stays on the phone — the board is a thin renderer.

---

## ⭐ ACTION REQUIRED (Android developer) — status 2026-07-08 EVENING (live-test update), in priority order

> **Live test result (phone connected, route created + cancelled):** RouteSummary +
> **RouteChunk (29 pts — decoded & rendered!)** + 1 NavInstruction + the `cancelled`
> event all arrived and worked. Still missing/broken in this build: **the ≤1 Hz
> heartbeat (item 1 — nothing between route-create and cancel)**, `streetName`
> carries maneuver text, `destinationName` empty. `seq` did increment on the cancel
> frame (0→1) — good.

**No API key / tile service is involved anywhere in this integration.** The board
renders a styled offline map and overlays LIVE DATA (position marker, distance
countdowns, ETA, maneuver banner). "Real-time" = the app streaming the frames below.
Transport, CRC, and decode are **proven end-to-end with your own frames**: on
2026-07-08 the board received and correctly rendered your RouteSummary +
NavInstruction (`dist=4219m/eta=510s` displayed as `4.2 km / 8 min`, matching the
phone). What is missing is exactly this:

### 1. STREAM the NavInstruction heartbeat (the one change that makes it real-time)

Observed live: the app sends **one burst when the route is created, then nothing**
(60 s watch while the route was active on the phone: zero further writes). The board
declares the feed dead after 15 s by design. Implement the §5 cadence in
`RouteNavigator`:

- **≤1 Hz** `NavInstruction` while moving (countdown heartbeat — full state, not deltas);
- **0.2 Hz** (every 5 s) when stationary (`speedKmh < 2`);
- **immediately** on maneuver change, state change (rerouting/offRoute/arrived/cancelled);
- **on BLE reconnect** mid-route: re-send the cached `RouteSummary`, then the next instruction.

Write-Without-Response, one frame per GATT write (§2/§6 — the §9 golden frame is the
byte-exact reference). The board interpolates between your 1 Hz checkpoints using its
own speed, so this cadence is smooth on screen.

### 2. Fix three field encodes (seen in your live frames)

| Field | You sent | Must be |
|---|---|---|
| `streetName` | `"Turn right"` (the maneuver text) | the **road you turn onto** (e.g. `"Jl. Sudirman"`); the maneuver comes from the `maneuver` enum — the board shows both, so today it prints "Turn right - Turn right" |
| `destinationName` (RouteSummary) | `""` | the destination label (e.g. `"Pacing"`) — the board's bottom-card title shows "--" until this is non-empty |
| `seq` | stuck at `0` | rolling counter, +1 per frame (stale/out-of-order guard) |

### 3. `RouteChunk` — ✅ WORKING END-TO-END (verified live 2026-07-08 evening)

**GREENLIT and shipped on both sides.** The board now decodes `navType 0x03`,
reassembles chunks per `routeId` (out-of-order tolerated), and **renders the received
polyline + destination pin on the maps screen**, driving the live position marker
along the real geometry. Your app's first real RouteChunk (`route=1, 29 points,
1 chunk`) was received, decoded, and rendered during the live test — geometry,
start point, and direction all correct. Keep doing exactly what you do; for longer
routes: downsample to ~1 point per 25–50 m, **≤45 points per chunk** stays under the
240 B frame cap, Write-With-Response, resend on reroute with the bumped `routeId`.

**Note on what the rider sees (sets expectations for QA):** the board draws YOUR
route shape and live position on a *stylized offline base map* — the streets/labels
around the route are decorative, not real imagery. The dot's position ON the route is
real (distance-based). Pixel-identical base imagery is the separate map-stream
feature ([`MAP-STREAM-INTEGRATION.md`](MAP-STREAM-INTEGRATION.md)).

**Reference-verified golden frame** (encoder validated byte-exact against the §9
goldens): `routeId=287454020, anchorLatE7=-75123456, anchorLonE7=1124567890, index=0,
total=1, deltas=[1500,0, 0,-1100, -1660,-80, 0,-620, -1800,-200]` (5 points, 1e-5 deg):

```
AA 41 03 00 00 00 00 07 00 00 00 00 00 00 00 02 00 01 00 44 33 22 11 00 B5 85 FB 52
8B 07 43 00 01 00 00 01 00 00 00 53 00 00 00 DC 05 00 00 00 00 B4 FB 84 F9 B0 FF 00
00 94 FD F8 F8 38 FF 00 00 00 00 31 74
```
(69 bytes; `LEN=0x41=65`, `navType=0x03`, CRC=`0x7431`.)

### Instant verification (no board tooling needed)

While connected, every accepted RouteSummary prints a line in the board's
`/var/log/ble-gatt.log`, and `/var/run/nav_state`'s mtime must tick at your heartbeat
rate (`ssh root@192.168.42.1`, password `root`). If the heartbeat works, the maps
screen goes LIVE within 1 s and stays live.

---

## 1. BLE transport

| Item | Value |
|---|---|
| Role | Board is a **BLE GATT peripheral**; the app is the central/client |
| Device name | **`EVdisplay`** (exact case) |
| Service UUID | **`0xAF00`** (16-bit) |
| **Navigation RX characteristic (app WRITES)** | **`0xAF06`** (16-bit) — **new** |
| RX properties | **`0x0C` = Write (0x08) \| Write-Without-Response (0x04)** |
| Notification RX (unchanged) | `0xAF07` — `PhoneNotification` only |
| Telemetry TX (unchanged) | `0xAF08` NOTIFY — VOTOL uplink |
| Pairing/bonding | **None. Open, unencrypted GATT.** |
| MTU | Board requests `247` on connect; negotiated = min(yours, 247) |

**Delivery model (identical to the notification channel):**
- **One message = one GATT write of one complete frame.** No reassembly on the board.
- **Frame ≤ (negotiated MTU − 3) ≤ 244 bytes; payload ≤ 240 bytes.**
- Every nav message fits one frame by design (§6).

**Capability negotiation = GATT discovery.** No board→phone handshake exists. On
connect the app discovers characteristics:
- **`0xAF06` present ⇒ navigation supported** → the app streams nav.
- **`0xAF06` absent** (old firmware) ⇒ app disables navigation send; notifications and
  telemetry keep working. New board + old app = the char exists but the app never
  writes it. Graceful degradation both ways. (On the phone this falls out naturally:
  `writeNav()` returns `false` when the characteristic is missing.)

---

## 2. Frame wrapper

Reuse the **exact** shared frame wrapper (`FrameEncoder` / `Crc16`):

```
[0xAA] [LEN] [ navType(1) ‖ capnp payload : LEN-1 bytes ] [ CRC16 : 2 bytes LE ]
  1  +  1  +                    LEN                        +          2
```

- `0xAA` — sync byte.
- `LEN` — one byte, total payload length `= 1 (navType) + capnp size`, `0..255`.
- `navType` — **1-byte tag** (first payload byte):

  | `navType` | Message |
  |---|---|
  | `0x01` | `RouteSummary` |
  | `0x02` | `NavInstruction` |
  | `0x03` | `RouteChunk` (**live** since 2026-07-08 — board renders the polyline) |

- `payload[1..]` — the **unpacked, single-segment** Cap'n Proto message for that type.
- `CRC16` — **CRC16-CCITT** (poly `0x1021`, init `0xFFFF`, no final XOR, MSB-first)
  over **`LEN` ‖ payload** (i.e. `LEN ‖ navType ‖ capnp`), sent low byte first. Board
  recomputes over `LEN‖payload`; drops on mismatch.

---

## 3. Cap'n Proto schema (`app/schema/navigation.capnp`)

Schema id `0xc4e1a9f30b7d2266`. **Ordinals are assigned in descending field size**
(4 B → 2 B → 1 B → Text) so the data section is compact/hole-free and the hand-built
writer maps 1:1 to byte offsets. Decode strictly by ordinal/name.

```capnp
enum NavState   { idle @0; navigating @1; rerouting @2; offRoute @3; arrived @4; cancelled @5; }
enum Maneuver   { none @0; depart @1; continueStraight @2;
                  turnSlightLeft @3; turnLeft @4; turnSharpLeft @5;
                  turnSlightRight @6; turnRight @7; turnSharpRight @8;
                  uTurn @9; keepLeft @10; keepRight @11; merge @12;
                  roundabout @13; rampLeft @14; rampRight @15; ferry @16; arrive @17; }

# navType 0x02 — ~1 Hz "current maneuver"; full state (not deltas), self-healing.
struct NavInstruction {
  routeId            @0  :UInt32;   # bumps on reroute / new route
  distanceRemainingM @1  :UInt32;
  etaSeconds         @2  :UInt32;
  seq                @3  :UInt16;   # rolling counter (stale/order detection)
  state              @4  :NavState;
  maneuver           @5  :Maneuver;
  distanceToTurnM    @6  :UInt16;
  nextManeuver       @7  :Maneuver; # look-ahead; none = unknown
  nextDistanceM      @8  :UInt16;
  roundaboutExit     @9  :UInt8;    # 0 = n/a
  speedLimitKmh      @10 :UInt8;    # 0 = unknown
  streetName         @11 :Text;     # road you turn ONTO (<= 40 UTF-8 B)
}

# navType 0x01 — once per route (start / reroute).
struct RouteSummary {
  routeId          @0 :UInt32;
  totalDistanceM   @1 :UInt32;
  totalDurationSec @2 :UInt32;
  maneuverCount    @3 :UInt16;
  polylineChunks   @4 :UInt8;       # RouteChunk count to expect (0 = none)
  schemaVersion    @5 :UInt8;       # v1 = 1
  destinationName  @6 :Text;        # <= 48 UTF-8 B
}

# navType 0x03 — LIVE end-to-end since 2026-07-08; the board draws the route line.
struct RouteChunk {
  routeId     @0 :UInt32;
  anchorLatE7 @1 :Int32;            # first point, 1e-7 deg
  anchorLonE7 @2 :Int32;
  index       @3 :UInt8;            # 0-based
  total       @4 :UInt8;
  deltas      @5 :List(Int16);      # [dLat,dLon,...] 1e-5 deg (~1.1 m) from running position
}
```

**Data-section byte offsets** (for a hand-decoder; add 16 for payload-absolute, i.e.
after the 8-byte segment table + 8-byte root pointer). These are what `capnp compile`
produces and what the writer emits:

`NavInstruction` — dataWords=4, ptrWords=1:

| Field | struct byte | size |
|---|---|---|
| routeId @0 | 0 | u32 |
| distanceRemainingM @1 | 4 | u32 |
| etaSeconds @2 | 8 | u32 |
| seq @3 | 12 | u16 |
| state @4 | 14 | u16 (enum) |
| maneuver @5 | 16 | u16 (enum) |
| distanceToTurnM @6 | 18 | u16 |
| nextManeuver @7 | 20 | u16 (enum) |
| nextDistanceM @8 | 22 | u16 |
| roundaboutExit @9 | 24 | u8 |
| speedLimitKmh @10 | 25 | u8 |
| (padding) | 26–31 | — |
| streetName @11 | ptr slot 0 | Text |

`RouteSummary` — dataWords=2, ptrWords=1:

| Field | struct byte | size |
|---|---|---|
| routeId @0 | 0 | u32 |
| totalDistanceM @1 | 4 | u32 |
| totalDurationSec @2 | 8 | u32 |
| maneuverCount @3 | 12 | u16 |
| polylineChunks @4 | 14 | u8 |
| schemaVersion @5 | 15 | u8 |
| destinationName @6 | ptr slot 0 | Text |

**Text encoding:** each `Text` is a non-null list pointer to the UTF-8 bytes **plus a
NUL terminator** (`elementCount = bytes + 1`, min 1, word-padded) — identical to
`capnp encode`, including the empty-string case (pointer to a 1-byte NUL list). Board
sanitizes on receipt (control chars → space; re-truncate to the caps).

### Extensibility contract (READ BEFORE EDITING)

Ordinals are **FROZEN** — never renumber, reorder, retype, or delete. New fields
**append** at the next ordinal; Cap'n Proto guarantees appended fields never move
existing ones (they fill trailing padding or grow the struct). Old firmware ignores
unknown trailing bytes/words; old phones leave new fields at default (0/empty). Honor
`LEN`; never assume a fixed size. `RouteSummary.schemaVersion` gives coarse gating if
a breaking change is ever unavoidable.

---

## 4. Phone-side architecture

```
 NavigationProvider (interface)   ← provider-specific adapter (the ONLY per-SDK file)
        │ Flow<NavProgress>          maps SDK maneuvers → Maneuver enum + NavState
        ▼
 RouteNavigator                    change-detection, throttle, routeId/seq, terminal
        │ NavInstruction / RouteSummary
        ▼
 NavigationSchema + FrameEncoder   hand-built capnp writer → framed bytes
        │ ByteArray
        ▼
 BluetoothDataSource.writeNav()    → BleGattClient.writeCommand(frame, 0xAF06, withResponse)
```

Implemented files:
- `domain/model/Navigation.kt` — `NavState`, `Maneuver` (with frozen `wire` values), `NavProgress`.
- `domain/repository/NavigationProvider.kt` — the provider-neutral seam.
- `data/protocol/NavigationSchema.kt` — the capnp writer (`frameNavInstruction`, `frameRouteSummary`).
- `data/navigation/RouteNavigator.kt` — orchestrator (§5).
- `data/navigation/SimulatedNavigationProvider.kt` — canned route for demos/tests.
- Transport: `BleConstants.NAV_CHAR_UUID`, generalized `BleGattClient.writeCommand`,
  `BluetoothDataSource.writeNav` (default no-op) + `BleDataSource`/`SwitchableDataSource`/
  `SimulatedDataSource` overrides.

`NavigationProvider` is the **only** class a routing SDK touches: translate its
route-progress / banner objects into `NavProgress` + `Maneuver`, surface off-route /
reroute / arrival as `NavState` transitions.

---

## 5. Update triggers (RouteNavigator)

| Trigger | Detection | Action |
|---|---|---|
| **Maneuver change** | maneuver key (routeId·maneuver·roundaboutExit) changed | `NavInstruction` **immediately** |
| **Distance countdown** | `distanceToTurnM` decreasing | **≤1 Hz** heartbeat; board interpolates between frames (§6) |
| **Stationary** | `speedKmh < 2` | **0.2 Hz** heartbeat |
| **State change** (off-route / rerouting / …) | `NavState` changed | send **immediately** |
| **Rerouting** | SDK recompute | `state=rerouting`, then **bump `routeId`** → new `RouteSummary` → resume |
| **Arrival** | SDK arrival | `state=arrived, distanceRemainingM=0` (**reliable**); end session |
| **Cancellation** | user stops | `state=cancelled` (**reliable**); clear nav UI |
| **BLE reconnect mid-nav** | transport → CONNECTED while active | re-send cached `RouteSummary`, force next instruction |

`routeId` = the board's "discard and re-learn" signal; `seq` = per-frame ordering/stale guard.

---

## 6. Frequency & bandwidth

- **Frame size** ≈ 100–120 B (`NavInstruction`); ceiling **240 B**. Caps: `streetName`
  ≤ 40 B, `destinationName` ≤ 48 B.
- **Rate**: event-driven maneuver/state sends **+ 1 Hz** countdown, **0.2 Hz**
  stationary. At 1 Hz ≈ **~0.1 KB/s** — negligible; the constraint is frame size and
  not spamming, never throughput.
- **Board interpolation is the key lever**: the board already receives live `speedKmh`
  telemetry, so it can decrement `distanceToTurnM` / `distanceRemainingM` / `etaSeconds`
  between phone frames and snap to the phone's value on each update. Phone sends
  ground-truth checkpoints; board renders smooth motion → the phone can stream as slow
  as 0.5–1 Hz with no stutter.
- **Write mode**: `NavInstruction` stream → **Write-Without-Response** (`reliable=false`,
  self-healing full-state frames). `RouteSummary` / terminal / `RouteChunk` →
  **Write-With-Response** (`reliable=true`). Writes are serialized (one GATT op at a time).

---

## 7. Firmware processing & rendering (IMPLEMENTED — deployed 2026-07-07/08)

Mirrors the notification handler (`handle_downlink_write` → `/var/run/phone_notification`
→ LVGL poll). Note: the actual IPC path is **`/var/run/nav_state`** (not the
`/var/run/phone_nav` originally sketched here) — a key=value file, one pair per
line (`seq route_id state maneuver dist_turn_m next_maneuver next_dist_m rb_exit
limit_kmh street dist_rem_m eta_s dest recv_ms`), atomically replaced on every
accepted frame; its mtime is the liveness signal (fresh <5 s = LIVE, 5–15 s =
dimmed STALE, >15 s = NAV OFFLINE).

1. **Characteristic `0xAF06`** with a write handler; validates the same
   `[0xAA][LEN][payload][CRC16-LE]` frame (reuses the existing CRC path). Reads
   `payload[0] = navType`; decodes `payload[1..]` as the matching struct. Publishes
   atomically to `/var/run/nav_state`.
2. **Freshness** — keep `recv_ms` + `seq`; ignore a frame whose `seq` is older than
   current (16-bit wraparound). `now − recv_ms > 5 s` → mark nav stale/dim.
3. **`routeId` change** → clear cached maneuver / route-line state; new route.
4. **Local interpolation** — decrement distances by `speedKmh · dt` (clamp ≥ 0),
   re-snap on each new `NavInstruction`.
5. **Render (LVGL)** — nav card: `maneuver` → arrow icon (fixed table), big
   `distanceToTurnM`, `streetName` line, ETA/remaining footer, `roundaboutExit` → "exit N",
   optional "then ⤴" chip when `nextManeuver ≠ none`.
6. **State** — `navigating`→show; `rerouting`→"Rerouting…"; `offRoute`→"Off route";
   `arrived`→"Arrived" then auto-clear; `cancelled`/`idle`→hide.
7. **Coexistence** — nav is its own IPC file/widget, independent of the notification
   banner. Board decides layout priority.
8. **Route line — IMPLEMENTED (2026-07-08):** `RouteChunk`s are buffered by
   `routeId`+`index` (≤16 chunks × 65 points, out-of-order OK); when complete the
   polyline is published atomically to **`/var/run/nav_route`**
   (`route_id=`/`chunks=`/`points=`/`complete=1`, then one `p=<latE7>,<lonE7>` per
   point, then `recv_ms=`). The UI fits it north-up (aspect-preserving,
   cos(lat)-corrected), swaps to a route-free base art, draws the real polyline +
   destination pin, and drives the position dot along it; falls back to the stylized
   route when absent/mismatched. Buffer resets on `routeId` change.

**Constraint:** the server has **no RX reassembly** — one write = one whole frame.
Every nav message fits one frame by design; keep text within the caps so no frame
exceeds 240 B.

---

## 8. Backward compatibility

- **`telemetry.capnp` untouched** (frozen).
- **`PhoneNotification` path untouched** — dedicated `0xAF06` means the verified
  `0xAF07` decode path and its golden frame don't change. Nav is purely additive.
- **Capability = GATT discovery** (§1). Old board / new app and new board / old app
  both degrade gracefully, no handshake.
- **Within the nav schema** — freeze ordinals; append at higher ordinals; board honors
  `LEN`, ignores unknown trailing bytes (same rule as telemetry). `schemaVersion` for
  coarse gating.

---

## 9. Golden frames (reference-compiler verified)

Produced by `capnp encode navigation.capnp …` (v1.0.1) for the payload, wrapped in the
§2 frame. The phone encoder reproduces these **byte-exactly** (`NavigationFrameTest`).
Use them as the firmware decoder's self-test.

**`NavInstruction`** — `routeId=287454020, distanceRemainingM=5300, etaSeconds=840,
seq=7, state=navigating, maneuver=turnLeft, distanceToTurnM=200, nextManeuver=turnRight,
nextDistanceM=45, roundaboutExit=0, speedLimitKmh=50, streetName="Jl. Sudirman"`:

```
AA 49 02 00 00 00 00 08 00 00 00 00 00 00 00 04 00 01 00 44 33 22 11 B4 14 00 00
48 03 00 00 07 00 01 00 04 00 C8 00 07 00 2D 00 00 32 00 00 00 00 00 00 01 00 00
00 6A 00 00 00 4A 6C 2E 20 53 75 64 69 72 6D 61 6E 00 00 00 00 B5 14
```
(77 bytes; `LEN=0x49=73`, `navType=0x02`, CRC=`0x14B5`.)

**`RouteSummary`** — `routeId=287454020, totalDistanceM=5300, totalDurationSec=840,
maneuverCount=12, polylineChunks=0, schemaVersion=1, destinationName="Kantor"`:

```
AA 31 01 00 00 00 00 05 00 00 00 00 00 00 00 02 00 01 00 44 33 22 11 B4 14 00 00
48 03 00 00 0C 00 00 01 01 00 00 00 3A 00 00 00 4B 61 6E 74 6F 72 00 00 F3 E1
```
(53 bytes; `LEN=0x31=49`, `navType=0x01`, CRC=`0xE1F3`.)

---
*Owner: mobile + firmware · Transport is provider-independent · Companion:
[`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md) · Wire framing
shared with `telemetry.capnp` / `PhoneNotification`.*
