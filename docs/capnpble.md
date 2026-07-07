# EVdisplay BLE link — Cap'n Proto over GATT (0xAF00 profile) + odometer

The **SG2002 "brain"** is a **BLE peripheral / GATT server**; a phone (Android/iOS) is
the central. The board **streams parsed VOTOL telemetry to the phone** (Notify) and
**accepts commands + phone-notification mirroring from the phone** (Write), both
serialized with **Cap'n Proto** and wrapped in the **same CRC-checked resync frame** as
the STM32→SG2002 UART link. This document is the BLE analogue of
`slavecode/capnp.md`: everything a phone-app or a board-side tool needs to speak the
link — **including the odometer** (`odo_km`/`trip_km` and the trip-reset command).

> Status: BLE transport + both capnp directions are **implemented and live on hardware**
> (`ble-gatt-server`, AIC8800D80, device name `EVdisplay`). The **trip-reset command**
> (`category=32 / ODO_RESET_TRIP`) is deployed and verified, and the odometer *values*
> ride on the uplink as `odoMeters` @12 / `tripMeters` @13 (§5c).
>
> ⚠️ **2026-07-07 — WIRE-BREAKING SCHEMA RENUMBER.** `VotolTelemetry` was re-sorted to
> sequential ordinals **@0..@14** (declaration order) and gained two fields
> (`batteryCurrent` @2, `tempBattery` @8). Every field's byte offset moved; a phone app
> built against the old ordinals (batteryPercent @10, odo @11/@12, …) decodes GARBAGE and
> **must regenerate from the new `votol_telemetry.capnp`**. This was a deliberate one-time
> renumber done before any external reader was locked; from now on @0..@14 are FROZEN.
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
| `0x1800` GAP | Device Name | `0x2A00` | Read | — | `0x0003` | "EVdisplay" |

- **To receive telemetry:** connect, (request MTU 247), then subscribe to `0xAF08` by
  writing `0x0001` to its CCCD (`0x2902`). Streaming starts immediately (~10 Hz); there
  is no "start" command.
- **To send a command / notification:** write one whole frame (§2) to `0xAF07`.
- **MTU sizing:** a telemetry frame is 52 bytes (48-byte payload + 4 framing) → fits one
  notification once **ATT_MTU ≥ 55**. Android **must** call `requestMtu(247)`; iOS negotiates ~185
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
  fields are appended). Telemetry = 48 (`0x30`); a notification can be up to ~240.
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
  tripMeters     @13 :UInt32;    # resettable trip odometer, METRES (÷1000 = km) (§5)
  flags          @14 :UInt8;     # see bit table
}
```

> **Ordinals are sequential (@0..@14 = declaration order)** since the 2026-07-07
> renumber. Still decode strictly by field **name / @ordinal**, never by textual
> position. Layout was regenerated in the shared C bindings and **cross-validated with
> pycapnp** (all 15 fields matched). Wire size: 32-byte data section, 48-byte capnp
> payload (LEN `0x30`), 52-byte framed.

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
| tripMeters     | 13 | UInt32 | **resettable trip** odometer, metres (÷1000 = km) | **CONFIRMED** — SG2002-integrated (§5) |
| flags          | 14 | UInt8  | bit0 run, 1 brake, 2 moving, 3 reverse, 4 park, 5 sideStand (N/A→0), 6 lowBattery (SoC≤15%), 7 regen (neg currentMotor) | run/brake/moving/reverse/park CONFIRMED; regen works at speed, threshold needs a road ride |

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

### Forward-compatibility contract (READ BEFORE EDITING SCHEMA)
Ordinals **@0..@14 are frozen** — never renumber, reorder, retype, or delete them (the
2026-07-07 renumber was the one-time exception, done before any external reader locked).
New telemetry is **appended** at @15, @16, … Old senders leave new fields at 0; old
readers ignore unknown trailing bytes. Honor `LEN`, never assume a fixed length.

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

## 5. Odometer over BLE

The VOTOL controller sends **only instantaneous speed** — it has no total-distance field
(see `slavecode/capnp.md` §3 and `votol-research.md`). The odometer is therefore
**integrated on the SG2002** (`telemetry-reader`, `d = speed_kmh · dt/3600`, monotonic
clock, `dt` clamped ≤600 ms) and persisted to **`/etc/odometer`** (`total_m=`/`trip_m=`,
integer metres, atomic write). It is exposed as two IPC keys in `/var/run/telemetry`:

| Key | Type | Meaning |
|---|---|---|
| `odo_km`  | float, 2 dp | **lifetime** odometer, km — never resets |
| `trip_km` | float, 2 dp | **resettable trip** odometer, km |

Both are always valid to display (they persist across reboots; only the *rate* of change
is live). Accuracy tracks the PROVISIONAL `kmh` calibration.

### 5a. Trip reset — one flag, three triggers
Resetting the trip is a single mechanism: **create the flag file
`/var/run/odo_reset_trip`**; `telemetry-reader` zeroes the trip on its next loop
(≤100 ms), persists it, and deletes the flag. The lifetime total is never touched.
Triggers that all write the same flag:
1. **Terminal:** the `odo-reset-trip` CLI on the board.
2. **Phone app over BLE:** the control command below.
3. (internal) the reader itself consuming the flag.

### 5b. Trip reset over BLE — `category = 32` control command (IMPLEMENTED)
A downlink `PhoneNotification` with **`category = 32`** is a board **CONTROL COMMAND**,
not a banner — the board routes it away from the notification path (no on-screen alert).
The command name rides in the **`title`** field; `appName`/`body` are ignored
(set `appName = "EVD"`).

| `title` | action |
|---|---|
| `ODO_RESET_TRIP` | zero the resettable trip odometer (lifetime total untouched). Board touches `/var/run/odo_reset_trip`; reader zeroes + persists. Same effect as the on-board `odo-reset-trip` CLI. |

Unknown `category=32` titles are logged and ignored (safe no-op). Add new commands by
adding a `title` row here and a branch in `handle_command()` in `ble-gatt-server.c`.
**To send it:** build a normal `PhoneNotification` frame with `category=32`,
`title="ODO_RESET_TRIP"`, and write it to `0xAF07`.

### 5c. Odometer *values* on the uplink — `odoMeters` @12 / `tripMeters` @13 (IMPLEMENTED)
The odometer is carried to the phone as two `UInt32` fields on `VotolTelemetry` (§3), in
**metres** (÷1000 = km) — metres match `/etc/odometer` and a UInt32 won't overflow on high
mileage. `ble-gatt-server`'s `read_snapshot()` parses the `odo_km`/`trip_km` snapshot keys
and its `encode_telemetry()` writes `odoMeters`/`tripMeters` (×1000). The uplink payload is
**48 bytes** (LEN `0x30`) — always honor `LEN`. (These fields were @11/@12 before the
2026-07-07 renumber; they are now @12/@13, wire offsets 24/28.)

```capnp
  odoMeters   @12 :UInt32;   # lifetime odometer, metres (÷1000 = km). SG2002-integrated.
  tripMeters  @13 :UInt32;   # resettable trip odometer, metres (÷1000 = km).
```

A phone reads distance as `odoMeters/1000.0` km and `tripMeters/1000.0` km, and resets the
trip via the §5b command. (The STM32/UART sender doesn't know the odometer, so on that link
these read 0; the board fills them from its own integrator before BLE transmission.)

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
(pycapnp, capnp-java, SwiftCapnp, …). To reset the trip, encode a `PhoneNotification`
(`category=32`, `title="ODO_RESET_TRIP"`), frame it, and write to `0xAF07`.

---

## 7. Reference frames (decoder unit tests)

**Uplink — `VotolTelemetry`** `{batteryVolt=773 (77.3V), batteryPercent=64,
batteryCurrent=4, currentMotor=-6, rpm=2710, kmh=42, tempControl=48, tempMotor=46,
tempBattery=33, driveMode=2, faultCode=0, seq=12345, odoMeters=0, tripMeters=0,
flags=0x11}` → 52-byte frame, LEN=`0x30`, CRC=`0x8DB0` (generated by the shared C
bindings, **cross-validated with pycapnp** — all 15 fields decoded correctly):
```
AA 30 00 00 00 00 05 00 00 00 00 00 00 00 04 00
00 00 05 03 40 04 FA FF 96 0A 2A 00 30 2E 21 02
11 00 00 00 00 00 39 30 00 00 00 00 00 00 00 00
00 00 B0 8D
```
- 48-byte payload: segment header `00 00 00 00 05 00 00 00` (1 seg, 5 words), root ptr
  `00 00 00 00 04 00 00 00` (struct, 4 data words), then the 32-byte data section by
  offset: `batteryVolt@0=773`, `batteryPercent@2=64`, `batteryCurrent@3=4`,
  `currentMotor@4=-6`, `rpm@6=2710`, `kmh@8=42`, `tempControl@10=48`, `tempMotor@11=46`,
  `tempBattery@12=33`, `driveMode@13=2`, `flags@14=0x11`, `faultCode@16=0`,
  `seq@20=12345`, `odoMeters@24=0`, `tripMeters@28=0`
> A live BLE frame carries real odometer values, e.g. `odo_km=456.78` →
> `odoMeters=456780` → `4C F8 06 00` at offset 24, `trip_km=12.34` → `tripMeters=12340`
> → `34 30 00 00` at offset 28 (little-endian UInt32).
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

**Trip-reset command** — a `PhoneNotification` `{category=32, appName="EVD",
title="ODO_RESET_TRIP"}` framed and written to `0xAF07` (build it with the c-capnproto
encoder, or the generator in `bt-telemetry/`), zeroes the trip on the board.

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
                  f"trip={m.tripMeters/1000:.2f}km seq={m.seq}")

async def main():
    dev = await BleakScanner.find_device_by_name("EVdisplay", timeout=10)
    async with BleakClient(dev) as c:
        await c.start_notify(AF08, on_notify)
        await asyncio.sleep(30)
asyncio.run(main())
```
