# EVdisplay BLE link — Cap'n Proto over GATT (0xAF00 profile) + odometer

The **SG2002 "brain"** is a **BLE peripheral / GATT server**; a phone (Android/iOS) is
the central. The board **streams parsed VOTOL telemetry to the phone** (Notify) and
**accepts commands + phone-notification mirroring from the phone** (Write), both
serialized with **Cap'n Proto** and wrapped in the **same CRC-checked resync frame** as
the STM32→SG2002 UART link. This document is the BLE analogue of
`slavecode/capnp.md`: everything a phone-app or a board-side tool needs to speak the
link — **including the odometer** (`odo_km` + the two trip meters `trip_a_km`/`trip_b_km`
and their reset commands).

> Status: BLE transport + both capnp directions are **implemented and live on hardware**
> (`ble-gatt-server`, AIC8800D80, device name `EVdisplay`). The **trip-reset commands**
> (`category=32 / ODO_RESET_TRIP_A` / `ODO_RESET_TRIP_B`, legacy `ODO_RESET_TRIP` = trip A)
> are implemented, and the odometer *values* ride on the uplink as `odoMeters` @12 /
> `tripAMeters` @15 / `tripBMeters` @16 (+ legacy alias `tripMeters` @13) (§5c).
>
> ⭐ **2026-07-14 — SCHEMA EXTENDED TO @0..@23 (append-only).** Two later extensions are
> folded into §3 below: the **2026-07-13 OTA fields** `fwVersion` @17 / `otaState` @18 /
> `otaProgress` @19 (BOARD→app TX-only), and the **2026-07-14 multi-motor fields**
> `phaseA` @20 / `phaseC` @21 / `power` @22 / `controllerType` @23. **`controllerType`
> @23 tells the app which controller is live — `0` = VOTOL EM-100, `1` = NANJING** — so it
> can label the **Model** and apply the per-type interpretation in §3. Data section grew
> 40 → 48 (OTA) → **56** B; framed payload 56 → 64 → **72** B (LEN `0x38` → `0x40` →
> **`0x48`**), frame **76** B. All append-only: `@0..@23` are FROZEN, apps **must honor
> `LEN`** and decode by ordinal — a decoder that knows only `@0..@16` or `@0..@19` keeps
> working (it ignores the extra trailing bytes; a shorter frame reads them as 0).
>
> ✅ **2026-07-07 (later) — APPEND-ONLY TRIP A/B EXTENSION.** Two independent trip meters
> replaced the single trip: `tripAMeters` **@15** and `tripBMeters` **@16** were APPENDED
> (no existing field moved — old apps keep decoding). Payload grew 48 → **56 B**
> (LEN `0x30` → **`0x38`**) — decoders that honor `LEN` are unaffected. `tripMeters` @13
> now always mirrors trip A, so a pre-A/B app still shows a working trip.
>
> ⚠️ **2026-07-07 — WIRE-BREAKING SCHEMA RENUMBER.** `VotolTelemetry` was re-sorted to
> sequential ordinals **@0..@14** (declaration order) and gained two fields
> (`batteryCurrent` @2, `tempBattery` @8). Every field's byte offset moved; a phone app
> built against the old ordinals (batteryPercent @10, odo @11/@12, …) decodes GARBAGE and
> **must regenerate from the new `votol_telemetry.capnp`**. This was a deliberate one-time
> renumber done before any external reader was locked; from now on @0..@16 are FROZEN.
> `ble-gatt-server` compiles against the same regenerated bindings
> (`slavecode/lib/capnp/`) as the STM32, so both links speak the new layout.
> Related: `votol_telemetry.capnp`, `phone_notification.capnp`, `ble-gatt/ble-gatt-server.c`,
> `stm32-link/TELEMETRY_SYNC.md`, and the app-facing companions
> `ble-gatt/EVDISPLAY_BLE_COMMUNICATION.md` + `ble-gatt/APP-NOTIFICATION-INTEGRATION.md`.
> This supersedes the Classic-SPP `bt-telemetry/capnp.md`, `DOWNLINK.md`, and the
> Nordic-UART `ble-gatt/BLE-TRANSPORT.md` (whose `6E4000xx` UUIDs are obsolete).

---

## 1. BLE transport (GATT profile)

| Property | Value |
|---|---|
| Role | Board = peripheral / GATT server; phone = central |
| Radio | AICSemi **AIC8800D80** (BLE 5.x) on the SG2002 |
| Device name | **`EVdisplay`** (Complete Local Name, in scan response) |
| Address | **PUBLIC, stable per unit** (e.g. `4C:A3:8F:14:D9:57`) — no RPA rotation |
| Advertising | connectable `ADV_IND`, 16-bit service UUID **`0xAF00`** (AD type 0x03), ~100–150 ms |
| Security | **NONE** — open GATT, no pairing/bonding |
| ATT channel | L2CAP fixed **CID `0x0004`** |
| MTU | board `MY_MTU = 247`; board initiates Exchange-MTU on connect; effective = min(central, 247) |

**GATT table** — 128-bit UUIDs are `0000xxxx-0000-1000-8000-00805f9b34fb`:

| Service | Characteristic | UUID | Properties | Descriptor | Value handle | Direction / payload |
|---|---|---|---|---|---|---|
| **`0xAF00`** | Telemetry **TX** | **`0xAF08`** | Notify | CCCD `0x2902` | `0x0006` | board → phone: `VotolTelemetry` |
| **`0xAF00`** | Command **RX** | **`0xAF07`** | Write + Write-No-Rsp (`0x0C`) | — | `0x0009` | phone → board: `PhoneNotification` |
| **`0xAF00`** | Navigation **RX** | **`0xAF06`** | Write + Write-No-Rsp (`0x0C`) | — | `0x000B` | phone → board: nav frames (§4b) |
| `0x1800` GAP | Device Name | `0x2A00` | Read | — | `0x0003` | "EVdisplay" |

Handles are **append-only** (phones cache the GATT DB): `0xAF06` was added 2026-07-07 at
the END of the table (decl `0x000A` / value `0x000B`, service group end moved
`0x0009`→`0x000B`); the pre-existing handles never move. Presence of `0xAF06` in
discovery = "this firmware supports navigation" (no handshake).

- **To receive telemetry:** connect, (request MTU 247), then subscribe to `0xAF08` by
  writing `0x0001` to its CCCD (`0x2902`). Streaming starts immediately (~10 Hz); there
  is no "start" command.
- **To send a command / notification:** write one whole frame (§2) to `0xAF07`.
- **MTU sizing:** a telemetry frame is currently 76 bytes (72-byte payload + 4 framing) → fits one
  notification once **ATT_MTU ≥ 79**. Android **must** call `requestMtu(247)`; iOS negotiates ~185
  automatically. At the 23-byte default the board chunks notifications into ≤20-byte
  pieces — reassemble as a byte stream through the frame decoder (§2), never per-packet.

---

## 2. Frame format (resync + integrity) — identical both directions

Identical to the USART1 framing in `slavecode/capnp.md`; the transport changed, the
frame did not.

```
+------+------+-------------------------+-------------+
| SYNC | LEN  |   PAYLOAD (LEN bytes)   |  CRC16 (LE) |
+------+------+-------------------------+-------------+
  0xAA   1 B      Cap'n Proto message       2 B
```

- **SYNC** = `0xAA` — frame-start marker; scan for it to resynchronise a byte stream.
- **LEN** = payload length in bytes (**always trust LEN**, never hardcode — it grows if
  fields are appended; it grew 48 → 56 with the trip A/B append, → 64 with the OTA fields
  @17..@19, → 72 with the multi-motor fields @20..@23). Telemetry is currently
  72 (`0x48`); a notification can be up to ~240.
- **PAYLOAD** = Cap'n Proto message, **unpacked, single segment** (what
  `capn_write_mem(..., packed=0)` emits: an 8-byte stream/segment header, then the data).
- **CRC16** = CRC16-CCITT (poly `0x1021`, init `0xFFFF`, MSB-first, **no** final XOR),
  computed over **LEN + PAYLOAD**, transmitted **little-endian** (low byte first).

Total frame length = `LEN + 4` bytes.

**Decoder algorithm (byte stream — required for the chunked-MTU case):**
1. Append received bytes to a buffer; scan until `0xAA`.
2. Read `LEN` (1 byte); wait until `LEN + 2` more bytes are buffered.
3. Take `LEN` payload bytes + 2 CRC bytes (little-endian).
4. Recompute CRC16 over `LEN || payload`; mismatch ⇒ drop one byte and go to step 1
   (a `0xAA` can occur inside a payload; a bad CRC just means keep scanning).
5. On match, decode the payload by its capnp schema **for that direction** (§3 / §4).

> **Never feed a frame to the wrong schema.** Board→phone frames are `VotolTelemetry`
> (file id `0xf0e5f2ff4f178d2a`); phone→board frames are `PhoneNotification`
> (file id `0xb39c7a21e4d05f68`). Direction is distinguished by characteristic, not content.

### CRC16-CCITT reference (C)
```c
uint16_t crc16_ccitt(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++)
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
    return crc;   // compare against the 2 received bytes read little-endian
}
```

---

## 3. Uplink schema — `VotolTelemetry` (Notify on `0xAF08`)

Board → phone, ~10 Hz (the board re-samples the STM32's ~20 Hz stream, so `seq` typically
advances by ~2 between notifications). `@0xf0e5f2ff4f178d2a`.

```capnp
struct VotolTelemetry {
  batteryVolt    @0  :UInt16;    # battery voltage, 0.1 V units (809 = 80.9 V)
  batteryPercent @1  :UInt8;     # state-of-charge, DIRECT 0-100 % (255 = not-yet-known on BLE)
  batteryCurrent @2  :Int8;      # battery PACK current, whole AMPS, signed, POSITIVE = charging
  currentMotor   @3  :Int16;     # motor current, deci-amps (÷10 = A), signed (neg = regen)
  rpm            @4  :UInt16;    # real motor RPM (already scaled ×4.5)
  kmh            @5  :UInt16;    # PROVISIONAL — km/h = raw * 83 / 1000
  tempControl    @6  :Int8;      # controller temp, whole °C
  tempMotor      @7  :Int8;      # motor temp, whole °C
  tempBattery    @8  :Int8;      # battery PACK temp, whole °C
  driveMode      @9  :UInt8;     # 1 / 2 / 3
  faultCode      @10 :UInt32;    # controller fault bitfield — PROVISIONAL, sent 0
  seq            @11 :UInt32;    # rolling counter, +1 per uplink frame (drop detection)
  odoMeters      @12 :UInt32;    # lifetime odometer, METRES (÷1000 = km). SG2002-integrated (§5)
  tripMeters     @13 :UInt32;    # LEGACY ALIAS: always mirrors tripAMeters @15 (§5)
  flags          @14 :UInt8;     # see bit table
  tripAMeters    @15 :UInt32;    # trip meter A, METRES (÷1000 = km), resettable (§5)
  tripBMeters    @16 :UInt32;    # trip meter B, METRES (÷1000 = km), independently resettable (§5)
  fwVersion      @17 :UInt32;    # board firmware, packed semver (major<<16)|(minor<<8)|patch — BOARD→app TX-only
  otaState       @18 :UInt8;     # OTA state enum 0..8 (0 IDLE … 7 SUCCESS, 8 FAILED) — BOARD→app TX-only
  otaProgress    @19 :UInt8;     # OTA progress 0..100 — BOARD→app TX-only
  phaseA         @20 :Int16;     # motor Phase-A current, DECI-AMPS (÷10 = A), signed — NANJING only (0 on VOTOL)
  phaseC         @21 :Int16;     # motor Phase-C current, DECI-AMPS (÷10 = A), signed — NANJING only (0 on VOTOL)
  power          @22 :UInt16;    # electrical power, WATTS (line current × battery volts) — NANJING only (0 on VOTOL)
  controllerType @23 :UInt8;     # 0 = VOTOL (EM-100), 1 = NANJING — the motor/controller MODEL; BOARD-sourced (/etc/motor_type)
}
```

> **Ordinals are sequential in declaration order for @0..@14** (2026-07-07 renumber);
> `tripAMeters` @15 / `tripBMeters` @16 were APPENDED later the same day (append-only —
> no existing offset moved). Still decode strictly by field **name / @ordinal**, never by
> textual position. Layout was regenerated in the shared C bindings and **cross-validated
> with pycapnp** (all 17 of the then-@0..@16 fields matched; hand-encoder output
> byte-identical to the official capnp encoder). Wire size (current @0..@23): **56-byte
> data section** — @15 at byte offset 32, @16 at 36, @17 fwVersion 40, @18 otaState 44,
> @19 otaProgress 45, @20 phaseA 48, @21 phaseC 50, @22 power 52, @23 controllerType 54 —
> **72-byte capnp payload** (LEN `0x48`), **76-byte framed**. A shorter legacy payload
> (e.g. a 48-byte pre-A/B or 64-byte pre-multi-motor sender) still decodes — the missing
> trailing fields read as 0.

| Field | @ | Type | Units / meaning | Status |
|-------|---|------|-----------------|--------|
| batteryVolt    | 0 | UInt16 | 0.1 V (÷10 → volts) | **CONFIRMED** (matched multimeter) |
| batteryPercent | 1 | UInt8  | 0-100 % SoC; **255 = not-yet-known** | **CONFIRMED** (matched bike display) |
| batteryCurrent | 2 | Int8   | whole **A**, signed, **positive = charging** into pack | **CONFIRMED on UART** (live charger unplug/replug). Forwarded over BLE: `ble-gatt-server` maps the reader's `batt_cur` snapshot key (2026-07-07) |
| currentMotor   | 3 | Int16  | deci-amps (÷10 = A), signed, neg = regen | **CONFIRMED** (÷10 matched vendor Display; ~−4 count zero-offset at idle) |
| rpm            | 4 | UInt16 | **real motor RPM** (STM32 pre-scales ×4.5) | **CONFIRMED** (mode1 WOT 2710, mode2 3830) |
| kmh            | 5 | UInt16 | km/h (`raw*83/1000`) | **PROVISIONAL** — needs road calibration |
| tempControl    | 6 | Int8   | controller °C | **CONFIRMED** (matches vendor Display) |
| tempMotor      | 7 | Int8   | motor °C | **CONFIRMED** |
| tempBattery    | 8 | Int8   | battery PACK °C (distinct from ctrl/motor) | CANDIDATE (~33 °C, corroborated; not heat-proven). Forwarded over BLE via the `batt_temp` snapshot key (2026-07-07) |
| driveMode      | 9 | UInt8  | 1 / 2 / 3 | **CONFIRMED** |
| faultCode      | 10 | UInt32 | bitfield | PROVISIONAL — sent 0 |
| seq            | 11 | UInt32 | frame counter | — |
| odoMeters      | 12 | UInt32 | **lifetime** odometer, metres (÷1000 = km) | **CONFIRMED** — SG2002-integrated (§5) |
| tripMeters     | 13 | UInt32 | **LEGACY ALIAS** — always mirrors `tripAMeters` @15 | kept for pre-A/B apps; prefer @15/@16 |
| flags          | 14 | UInt8  | bit0 run, 1 brake, 2 moving, 3 reverse, 4 park, 5 sideStand (N/A→0), 6 lowBattery (SoC≤15%), 7 regen (neg currentMotor) | run/brake/moving/reverse/park CONFIRMED; regen works at speed, threshold needs a road ride |
| tripAMeters    | 15 | UInt32 | **trip A**, metres (÷1000 = km), resettable (§5b) | SG2002-integrated (§5); appended 2026-07-07 |
| tripBMeters    | 16 | UInt32 | **trip B**, metres (÷1000 = km), independently resettable (§5b) | SG2002-integrated (§5); appended 2026-07-07 |
| fwVersion      | 17 | UInt32 | board firmware, packed semver `(major<<16)\|(minor<<8)\|patch` (V1.0.0 = 65536) | **BOARD→app TX-only** (STM32 sends 0); appended 2026-07-13 |
| otaState       | 18 | UInt8  | OTA state enum 0..8 (0 IDLE … 7 SUCCESS, 8 FAILED) | **BOARD→app TX-only**; appended 2026-07-13 |
| otaProgress    | 19 | UInt8  | OTA download/apply progress 0..100 | **BOARD→app TX-only**; appended 2026-07-13 |
| phaseA         | 20 | Int16  | motor **Phase-A** current, deci-amps (÷10 = A), signed | **NANJING only — 0 on VOTOL**; STM32-sourced; appended 2026-07-14 |
| phaseC         | 21 | Int16  | motor **Phase-C** current, deci-amps (÷10 = A), signed | **NANJING only — 0 on VOTOL**; STM32-sourced; appended 2026-07-14 |
| power          | 22 | UInt16 | electrical **power, WATTS** (line current × battery volts) | **NANJING only — 0 on VOTOL**; STM32-sourced; appended 2026-07-14 |
| controllerType | 23 | UInt8  | **0 = VOTOL (EM-100), 1 = NANJING** — the motor/controller **MODEL** | **BOARD-sourced** (from `/etc/motor_type`); STM32 never sends it; board→app TX-only; appended 2026-07-14 |

**Derived indicators (compute on the receiving side — no wire flag):**
- **`charging = batteryCurrent >= 1`** (positive amps = current into the pack). There is
  intentionally **no charging bit in `flags`** (byte is full). Add hysteresis so the icon
  doesn't flicker on the charger's 0→1→4 A ramp: on at **≥1 A**, off only after **≤0 A
  held ~1 s**. The board's `telemetry-reader` computes exactly this as the `charging=`
  key in `/var/run/telemetry` (forced 0 while the STM32 link is dead); the phone must
  derive it itself from `batteryCurrent`, which IS forwarded over BLE.

> **Startup:** `batteryPercent` comes from a ~1 Hz CAN frame and reads 0 for ~1 s after
> the STM32 boots; the board publishes **255** ("not yet known") until the first real
> value latches. Treat 255 as `--` / hold, not 0 %.

### VOTOL vs NANJING — per-controller interpretation (READ THIS)
The board drives **two** controller types over the **same** `0xAF08` characteristic and
the **same** schema. **`controllerType` @23 tells the app which one is live** — use it to
pick the on-screen **Model** label (`0` = VOTOL EM-100, `1` = NANJING) and to apply the
handling below. The app selects behaviour off @23, not off a separate message.

| Field(s) | VOTOL (`controllerType = 0`) | NANJING (`controllerType = 1`) |
|---|---|---|
| `controllerType` @23 | `0` | `1` — sets the Model label + the rows below |
| `currentMotor` @3 | deci-amps (÷10 = A) | deci-amps — **the board scales NANJING whole-amps ×10 before sending**, so @3 is deci-amps for BOTH. Treat @3 **identically**; no app-side per-type math. |
| `batteryCurrent` @2 | real, whole A, signed (+ = charging into pack) | **now REAL too** (was 0 for NANJING before 2026-07-14) |
| `tempBattery` @8, `faultCode` @10 | populated | **sent as 0** — not available on the NANJING link |
| `phaseA` @20, `phaseC` @21, `power` @22 | **0** | real: per-phase current (deci-amps) + electrical power (watts) |

So one decoder handles both: read @23, label the Model, read @3/@2 the same way for both,
and surface @8/@10 (VOTOL) or @20/@21/@22 (NANJING) only when they apply.

### Forward-compatibility contract (READ BEFORE EDITING SCHEMA)
Ordinals **@0..@23 are frozen** — never renumber, reorder, retype, or delete them (the
2026-07-07 renumber was the one-time exception, done before any external reader locked;
@15/@16 were appended per this contract the same day, @17..@19 for OTA on 2026-07-13, and
@20..@23 for multi-motor on 2026-07-14). New telemetry is **appended** at @24, @25, …
Old senders leave new fields at 0; old readers ignore unknown trailing bytes. Honor
`LEN`, never assume a fixed length.

---

## 4. Downlink schema — `PhoneNotification` (Write on `0xAF07`)

Phone → board, event-driven. One GATT write = one whole frame (§2). Frame ≤ MTU−3
(≤ 244 B), payload ≤ 240 B → **no RX reassembly on the board**. `@0xb39c7a21e4d05f68`.

```capnp
struct PhoneNotification {
  id            @0 :UInt32;   # phone-assigned id, stable per notification
  timestampUnix @1 :UInt32;   # unix seconds posted; 0 = unknown
  category      @2 :UInt8;    # 0 other 1 call 2 sms 3 app 4 email 5 calendar 6 clear ; 32 = CONTROL COMMAND
  flags         @3 :UInt8;    # bit0 ongoing, bit1 removed(dismiss), bit2 silent
  appName       @4 :Text;     # <= 24 UTF-8 bytes
  title         @5 :Text;     # <= 48 UTF-8 bytes  (also carries command name when category=32)
  body          @6 :Text;     # <= 96 UTF-8 bytes
}
```

| Field | @ | Type | Meaning |
|-------|---|------|---------|
| id            | 0 | UInt32 | stable id — correlate updates / dismissals |
| timestampUnix | 1 | UInt32 | unix seconds; 0 = unknown |
| category      | 2 | UInt8  | 0 other, 1 call, 2 sms, 3 app, 4 email, 5 calendar, 6 clear; ≥7 → other; **32 = control command (§5b)** |
| flags         | 3 | UInt8  | bit0 ongoing, bit1 removed (dismiss this `id`), bit2 silent |
| appName       | 4 | Text   | ≤ 24 B |
| title         | 5 | Text   | ≤ 48 B (command name when `category=32`) |
| body          | 6 | Text   | ≤ 96 B |

Board writes each decoded notification to `/var/run/phone_notification` (atomic
temp+rename, `key=value` lines, `seq` = freshness) for the LVGL banner UI. `category = 6`
= clear everything; `flags` bit1 = dismiss the single matching `id`.

---

## 4b. Navigation downlink — `0xAF06` (Write) → `/var/run/nav_state`

Phone → board turn-by-turn navigation, **separate message family on its own
characteristic** so the verified `0xAF07` notification path is untouched. Full
app-facing spec: `appdeveloper/NAVIGATION-INTEGRATION.md` (v1, golden frames
included); frozen schema copy: `bt-telemetry/navigation.capnp`
(`@0xc4e1a9f30b7d2266`). One GATT write = one whole frame (§2 wrapper), with a
1-byte **`navType`** tag as the first payload byte:

| `navType` | Message | Board action |
|---|---|---|
| `0x01` | `RouteSummary` | cache `destinationName`/`routeId`/totals (no file write) |
| `0x02` | `NavInstruction` | publish snapshot to `/var/run/nav_state` |
| `0x03` | `RouteChunk` | phase 2 — accepted + discarded |

So the frame is `[0xAA][LEN][navType ‖ capnp][CRC16-CCITT-LE]`, CRC over
`LEN‖navType‖capnp`. The board decodes with a **hand decoder** against the frozen
byte offsets (no capnp codec; honors `LEN`, ignores unknown trailing bytes —
append-only schema evolution). Semantics implemented in
`ble-gatt-server.c handle_nav_write()`:

- **Stale guard:** `NavInstruction.seq` is a rolling u16; a frame whose signed
  16-bit diff vs. the last accepted seq is ≤ 0 is dropped (wrap-aware).
- **`routeId` change** (instruction ≠ cached summary) clears the cached
  `RouteSummary` → `dest=` stays empty until the new route's summary arrives.
- **Sanitization:** control chars → space; `street` ≤ 40 B, `dest` ≤ 48 B.
- Bad sync/LEN/CRC, malformed capnp, unknown `navType` → logged silent drop
  (Write-Without-Response has no error path).

**IPC contract (FIXED — the maps/nav UI builds against exactly this).** Every
**accepted `NavInstruction`** atomically rewrites `/var/run/nav_state`
(tmp+rename; **file mtime = liveness** — UI treats mtime older than ~3–5 s as nav
stale/offline). Plain text, one `key=value` per line, exactly this order:

```
seq=<u16>            route_id=<u32>       state=<0..5>       maneuver=<0..17>
dist_turn_m=<u16>    next_maneuver=<0..17> next_dist_m=<u16>  rb_exit=<u8>
limit_kmh=<u8, 0=unknown>  street=<text, may be empty>
dist_rem_m=<u32>     eta_s=<u32>          dest=<text, empty until RouteSummary>
recv_ms=<CLOCK_MONOTONIC ms at receipt>
```

(state: 0 idle, 1 navigating, 2 rerouting, 3 offRoute, 4 arrived, 5 cancelled;
maneuver enum per `navigation.capnp`; out-of-range enum values from a newer phone
are written as 0.) Decoder unit test: the spec §9 golden frames (`NavInstruction`
CRC `0x14B5`, `RouteSummary` CRC `0xE1F3`) replayed through
`ble-gatt-server --att-test --navpath …` must yield exactly
`seq=7 route_id=287454020 state=1 maneuver=4 dist_turn_m=200 next_maneuver=7
next_dist_m=45 rb_exit=0 limit_kmh=50 street=Jl. Sudirman dist_rem_m=5300
eta_s=840 dest=Kantor`.

---

## 5. Odometer over BLE

The VOTOL controller sends **only instantaneous speed** — it has no total-distance field
(see `slavecode/capnp.md` §3 and `votol-research.md`). The odometer is therefore
**integrated on the SG2002** (`telemetry-reader`, `d = speed_kmh · dt/3600`, monotonic
clock, `dt` clamped ≤600 ms) and persisted to **`/etc/odometer`**
(`total_m=`/`trip_a_m=`/`trip_b_m=`, integer metres, atomic write; a legacy file with the
old single-trip `trip_m=` key MIGRATES on first load: old trip → trip A, trip B starts 0).
Since 2026-07-07 there are **two independent trip meters, A and B** — both integrate
distance identically; each resets independently. The IPC keys in `/var/run/telemetry`:

| Key | Type | Meaning |
|---|---|---|
| `odo_km`    | float, 2 dp | **lifetime** odometer, km — never resets |
| `trip_a_km` | float, 2 dp | **trip A**, km — resettable (was "the" trip pre-A/B) |
| `trip_b_km` | float, 2 dp | **trip B**, km — independently resettable |
| `trip_km`   | float, 2 dp | **LEGACY ALIAS** of trip A (kept for the existing LVGL UI) |

All are always valid to display (they persist across reboots; only the *rate* of change
is live). Accuracy tracks the PROVISIONAL `kmh` calibration.

### 5a. Trip reset — one flag per trip, three triggers
Resetting a trip is a single mechanism per trip: **create the flag file**; the reader
zeroes that trip on its next loop (≤100 ms), persists, and deletes the flag. The
lifetime total is never touched.

| Flag file | Effect |
|---|---|
| `/var/run/odo_reset_trip_a` | zero **trip A** |
| `/var/run/odo_reset_trip_b` | zero **trip B** |
| `/var/run/odo_reset_trip`   | LEGACY — zero **trip A** (pre-A/B compatibility) |

Triggers that write the same flags:
1. **Terminal:** the `odo-reset-trip [a|b]` CLI (default `a`; wrappers
   `odo-reset-trip-a` / `odo-reset-trip-b`).
2. **Phone app over BLE:** the control commands below.
3. (internal) the reader itself consuming the flag.

### 5b. Trip reset over BLE — `category = 32` control commands (IMPLEMENTED)
A downlink `PhoneNotification` with **`category = 32`** is a board **CONTROL COMMAND**,
not a banner — the board routes it away from the notification path (no on-screen alert).
The command name rides in the **`title`** field; `appName`/`body` are ignored
(set `appName = "EVD"`).

| `title` | action |
|---|---|
| `ODO_RESET_TRIP_A` | zero **trip A** (lifetime total untouched). Board touches `/var/run/odo_reset_trip_a`. Same effect as `odo-reset-trip a`. |
| `ODO_RESET_TRIP_B` | zero **trip B**. Board touches `/var/run/odo_reset_trip_b`. Same effect as `odo-reset-trip b`. |
| `ODO_RESET_TRIP`   | LEGACY (pre-A/B apps): zero **trip A** via the legacy flag `/var/run/odo_reset_trip`. New apps should send `ODO_RESET_TRIP_A`. |

Unknown `category=32` titles are logged and ignored (safe no-op). Add new commands by
adding a `title` row here and a branch in `handle_command()` in `ble-gatt-server.c`.
**To send one:** build a normal `PhoneNotification` frame with `category=32`,
`title="ODO_RESET_TRIP_A"` (or `_B`), and write it to `0xAF07`.

### 5c. Odometer *values* on the uplink — `odoMeters` @12 / `tripAMeters` @15 / `tripBMeters` @16 (IMPLEMENTED)
The odometer is carried to the phone as `UInt32` fields on `VotolTelemetry` (§3), in
**metres** (÷1000 = km) — metres match `/etc/odometer` and a UInt32 won't overflow on high
mileage. `ble-gatt-server`'s `read_snapshot()` parses the `odo_km`/`trip_a_km`/`trip_b_km`
snapshot keys and its `encode_telemetry()` writes `odoMeters`/`tripAMeters`/`tripBMeters`
(×1000), with `tripMeters` @13 always mirroring trip A. The uplink payload is
**72 bytes** (LEN `0x48`) as of the @17..@23 appends — always honor `LEN`. (odo/trip were
@11/@12 before the 2026-07-07 renumber, then @12/@13; trips A/B were appended at @15/@16,
wire offsets 32/36.)

```capnp
  odoMeters   @12 :UInt32;   # lifetime odometer, metres (÷1000 = km). SG2002-integrated.
  tripMeters  @13 :UInt32;   # LEGACY ALIAS: mirrors tripAMeters @15.
  tripAMeters @15 :UInt32;   # trip A, metres (÷1000 = km), resettable.
  tripBMeters @16 :UInt32;   # trip B, metres (÷1000 = km), independently resettable.
```

A phone reads distance as `odoMeters/1000.0` km and `tripAMeters/1000.0` /
`tripBMeters/1000.0` km, and resets each trip via the §5b commands. (The STM32/UART
sender doesn't know the odometer, so on that link these read 0; the board fills them
from its own integrator before BLE transmission.)

---

## 6. Client / reader side

### 6a. C decoder (board-side tools reusing the vendored runtime)
Reuse the generated files `lib/capnp/{votol_telemetry,phone_notification}.capnp.{c,h}` +
the runtime (`capn.c`, `capn-malloc.c`, `capn-stream.c`, `capnp_c.h`). Decode a
CRC-validated **payload** (SYNC/LEN/CRC already stripped):
```c
#include "capnp_c.h"
#include "votol_telemetry.capnp.h"
struct capn c;
if (capn_init_mem(&c, buf, len, 0 /*unpacked*/) != 0) { /* bad frame, drop */ }
VotolTelemetry_ptr rp; rp.p = capn_getp(capn_root(&c), 0, 1);
struct VotolTelemetry t; read_VotolTelemetry(&t, rp);
float volts = t.batteryVolt / 10.0f;
float amps  = t.currentMotor / 10.0f;   /* signed; negative = regen */
/* ... use t.seq to detect dropped notifications (should step by ~2) */
```
Encode a downlink `PhoneNotification` the same way with `new_PhoneNotification()` +
`write_PhoneNotification()` + `capn_write_mem()`, then wrap it in the §2 frame.

### 6b. Phone app (central)
Scan for name `EVdisplay` / service `0xAF00` → connect → `requestMtu(247)` →
enable notifications on `0xAF08` → feed every notification's bytes through the §2
byte-stream decoder → decode each frame as `VotolTelemetry` with a capnp library
(pycapnp, capnp-java, SwiftCapnp, …). To reset a trip, encode a `PhoneNotification`
(`category=32`, `title="ODO_RESET_TRIP_A"` or `"ODO_RESET_TRIP_B"`), frame it, and
write to `0xAF07`.

---

## 7. Reference frames (decoder unit tests)

**Uplink — `VotolTelemetry`** `{batteryVolt=773 (77.3V), batteryPercent=64,
batteryCurrent=4, currentMotor=-6, rpm=2710, kmh=42, tempControl=48, tempMotor=46,
tempBattery=33, driveMode=2, faultCode=0, seq=12345, odoMeters=0, tripMeters=1500,
tripAMeters=1500, tripBMeters=104500, flags=0x11}` → 60-byte frame, LEN=`0x38`,
CRC=`0xA996` (generated by the shared C bindings, **cross-validated with pycapnp** —
all 17 fields decoded correctly, hand-encoder output byte-identical to official capnp).
This 60-byte frame **predates the @17..@23 appends**, so `fwVersion`/`otaState`/
`otaProgress`/`phaseA`/`phaseC`/`power`/`controllerType` all read **0** in it — a current
`@0..@23` decoder must still accept it (honor `LEN`; missing trailing fields = 0). The
full 76-byte `@0..@23` golden frame will be regenerated from the shipped bindings:
```
AA 38 00 00 00 00 06 00 00 00 00 00 00 00 05 00
00 00 05 03 40 04 FA FF 96 0A 2A 00 30 2E 21 02
11 00 00 00 00 00 39 30 00 00 00 00 00 00 DC 05
00 00 DC 05 00 00 34 98 01 00 96 A9
```
- 56-byte payload: segment header `00 00 00 00 06 00 00 00` (1 seg, 6 words), root ptr
  `00 00 00 00 05 00 00 00` (struct, 5 data words), then the 40-byte data section by
  offset: `batteryVolt@0=773`, `batteryPercent@2=64`, `batteryCurrent@3=4`,
  `currentMotor@4=-6`, `rpm@6=2710`, `kmh@8=42`, `tempControl@10=48`, `tempMotor@11=46`,
  `tempBattery@12=33`, `driveMode@13=2`, `flags@14=0x11`, `faultCode@16=0`,
  `seq@20=12345`, `odoMeters@24=0`, `tripMeters@28=1500`, `tripAMeters@32=1500`,
  `tripBMeters@36=104500`
> A live BLE frame carries real odometer values, e.g. `odo_km=456.78` →
> `odoMeters=456780` → `4C F8 06 00` at offset 24; `trip_a_km=1.50` → `tripAMeters=1500`
> → `DC 05 00 00` at offsets 28 (legacy mirror) AND 32; `trip_b_km=104.50` →
> `tripBMeters=104500` → `34 98 01 00` at offset 36 (little-endian UInt32).
>
> The **pre-A/B 52-byte golden frame** (LEN `0x30`, CRC `0x8DB0`, same values with
> trips 0) is still VALID for a decoder test — a legacy 48-byte payload must decode
> cleanly with `tripAMeters`/`tripBMeters` = 0:
> ```
> AA 30 00 00 00 00 05 00 00 00 00 00 00 00 04 00
> 00 00 05 03 40 04 FA FF 96 0A 2A 00 30 2E 21 02
> 11 00 00 00 00 00 39 30 00 00 00 00 00 00 00 00
> 00 00 B0 8D
> ```
>
> ⚠️ The **old pre-renumber golden frame** (LEN `0x28`, CRC `0x09E6`, batteryPercent at
> @10) is **obsolete and must not be used** — the 2026-07-07 renumber moved every
> offset, so decoding it with the current schema yields garbage.

**Downlink — `PhoneNotification`** `{id=4242, timestampUnix=1751800000, category=2,
flags=0, appName="Messages", title="Alice", body="On my way"}` → 100-byte frame,
LEN=`0x60`, CRC=`0x1CE1`:
```
AA 60 00 00 00 00 0B 00 00 00 00 00 00 00 02 00 03 00 92 10 00 00 C0 58 6A 68
02 00 00 00 00 00 00 00 09 00 00 00 4A 00 00 00 0D 00 00 00 32 00 00 00 0D 00
00 00 52 00 00 00 4D 65 73 73 61 67 65 73 00 00 00 00 00 00 00 00 41 6C 69 63
65 00 00 00 4F 6E 20 6D 79 20 77 61 79 00 00 00 00 00 00 00 E1 1C
```

**Trip-reset commands** — a `PhoneNotification` `{category=32, appName="EVD",
title="ODO_RESET_TRIP_A"}` (or `"ODO_RESET_TRIP_B"`; legacy `"ODO_RESET_TRIP"` = trip A)
framed and written to `0xAF07` (build it with the c-capnproto encoder, or the generator
in `bt-telemetry/`), zeroes that trip on the board.

---

## 8. Python cross-check (optional)

Needs `pip install pycapnp bleak`. Subscribe to `0xAF08`, reassemble the byte stream,
decode `VotolTelemetry`:
```python
import asyncio, capnp
from bleak import BleakScanner, BleakClient
capnp.remove_import_hook()
tele = capnp.load('votol_telemetry.capnp')
AF08 = "0000af08-0000-1000-8000-00805f9b34fb"

def crc16(d, crc=0xFFFF):
    for b in d:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc

buf = bytearray()
def on_notify(_, data):
    buf.extend(data)
    while len(buf) >= 4:
        if buf[0] != 0xAA: buf.pop(0); continue
        L = buf[1]
        if len(buf) < 2 + L + 2: break
        payload = bytes(buf[2:2+L]); rx = buf[2+L] | (buf[3+L] << 8)
        good = rx == crc16(bytes(buf[1:2+L])); del buf[:2+L+2]
        if not good: continue
        with tele.VotolTelemetry.from_bytes(payload) as m:
            print(f"V={m.batteryVolt/10:.1f} SoC={m.batteryPercent}% "
                  f"Imot={m.currentMotor/10:.1f}A Ibat={m.batteryCurrent}A "
                  f"rpm={m.rpm} kmh={m.kmh} odo={m.odoMeters/1000:.2f}km "
                  f"tripA={m.tripAMeters/1000:.2f}km tripB={m.tripBMeters/1000:.2f}km "
                  f"seq={m.seq}")

async def main():
    dev = await BleakScanner.find_device_by_name("EVdisplay", timeout=10)
    async with BleakClient(dev) as c:
        await c.start_notify(AF08, on_notify)
        await asyncio.sleep(30)
asyncio.run(main())
```
