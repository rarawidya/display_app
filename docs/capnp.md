# VOTOL telemetry uplink — Cap'n Proto over USART1 (SG2002 adaptation guide)

The **STM32G431 slave** decodes the VOTOL EM-100 (LD-EM-V3) 250 kbit/s CAN broadcast
and streams parsed telemetry to the **SG2002 "brain"** over USART1, serialized with
**Cap'n Proto** and wrapped in a CRC-checked resync frame. This document is everything
the SG2002/EVDISPLAY firmware needs to implement the **reader** side.

> Status: the STM32 side is implemented and **verified end-to-end** — live frames were
> captured on the wire and decoded field-by-field with pycapnp, **0 CRC failures**.
> Related files: `votol_telemetry.capnp` (schema), `lib/capnp/` (generated decoder +
> runtime, reusable verbatim), `docs/sg2002_uplink.md` (long-form version of this doc).

---

## 1. Physical link
| Property | Value |
|---|---|
| UART | STM32 **PA9 = TX** → SG2002 **RX**, common **GND** |
| Params | **115200 baud, 8N1**, no flow control |
| Rate | one message every 50 ms (**20 Hz**) |
| Direction | STM32 → SG2002 only (telemetry; no downlink defined yet) |

The STM32 has three USART1 modes via compile-time `UPLINK_MODE` (`src/main.c`):
`RAW` (CAN hex dump), `DECODED` (human-readable ASCII), and **`CAPNP`** (the binary
frame below — the production mode). Only `CAPNP` produces this format.

---

## 2. Frame format (resync + integrity)

```
+------+------+-------------------------+-------------+
| SYNC | LEN  |   PAYLOAD (LEN bytes)   |  CRC16 (LE) |
+------+------+-------------------------+-------------+
  0xAA   1 B      Cap'n Proto message       2 B
```

- **SYNC** = `0xAA` — frame-start marker; scan for it to resynchronise.
- **LEN**  = payload length in bytes (currently **40**; **always trust LEN**, never hardcode — it grows if fields are appended).
- **PAYLOAD** = Cap'n Proto message, **unpacked, single segment** (exactly what `capn_write_mem(..., packed=0)` emits: an 8-byte stream/segment header, then the data).
- **CRC16** = CRC16-CCITT (poly `0x1021`, init `0xFFFF`, MSB-first, **no** final XOR), computed over **LEN + PAYLOAD**, transmitted **little-endian** (low byte first).

Total frame length = `LEN + 4` bytes.

**Reader algorithm:**
1. Read bytes until you see `0xAA`.
2. Read `LEN` (1 byte).
3. Read `LEN` payload bytes.
4. Read 2 CRC bytes (little-endian).
5. Recompute CRC16 over `LEN || payload`; if it mismatches, **discard and go back to step 1** (a `0xAA` can occur inside the payload — a failed CRC just means keep scanning; the next real frame resyncs).
6. On match, decode the payload (section 4).

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

## 3. Cap'n Proto schema (`votol_telemetry.capnp`)

```capnp
@0xf0e5f2ff4f178d2a;

struct VotolTelemetry {
  batteryVolt    @0  :UInt16;    # battery voltage, 0.1 V units (809 = 80.9 V) — CONFIRMED
  batteryPercent @10 :UInt8;     # state-of-charge, DIRECT 0-100 % — CONFIRMED
  currentMotor   @1  :Int16;     # motor current in DECI-AMPS (÷10 = A), signed (neg = regen) — CONFIRMED
  rpm            @2  :UInt16;    # real motor RPM (already scaled) — CONFIRMED (mode1 WOT 2710, mode2 3830)
  kmh            @3  :UInt16;    # PROVISIONAL — derived from raw: km/h = raw * 83 / 1000 (~0.083)
  tempControl    @4  :Int8;      # controller temp, whole deg C, direct — CONFIRMED
  tempMotor      @5  :Int8;      # motor temp, whole deg C, direct — CONFIRMED
  driveMode      @6  :UInt8;     # 1 / 2 / 3 — CONFIRMED
  faultCode      @7  :UInt32;    # controller fault bitfield — PROVISIONAL, currently sent 0
  flags          @8  :UInt8;     # bit0 run, bit1 brake, bit2 moving, bit3 reverse, bit4 park, bit5 sideStand(N/A), bit6 lowBattery, bit7 regen
  seq            @9  :UInt32;    # rolling counter, +1 per uplink frame (drop detection)
}
```

> **Field order vs @ordinals:** fields are listed in dashboard reading order; the `@N`
> ordinal (not the textual order) fixes each field's wire offset, so the ordinals look
> out of sequence on purpose. Decode strictly by field name / ordinal, never by position.

### Field meaning / units / trust
| Field | @ | Type | Units / meaning | Status |
|-------|---|------|-----------------|--------|
| batteryVolt      | 0 | UInt16 | 0.1 V (÷10 → volts) | **CONFIRMED** (80.9 V matched bike) |
| batteryPercent   | 10 | UInt8 | 0-100 % SoC | **CONFIRMED** (91% matched bike) |
| currentMotor     | 1 | Int16  | **deci-amps (÷10 = A)**, **signed** (neg = regen) | **CONFIRMED** — ÷10 matched the vendor Display; ~−4 count (~0.4 A) zero-offset at idle |
| rpm              | 2 | UInt16 | **real motor RPM** (already scaled ×4.5) | **CONFIRMED** vs display: mode1 WOT 2710, mode2 3830. Reader uses as-is, no scaling |
| kmh              | 3 | UInt16 | km/h (`raw*83/1000`) | **PROVISIONAL** — factor needs road calibration vs speedo |
| tempControl      | 4 | Int8   | whole °C, direct | **CONFIRMED** (CAN d5, swapped to match Display) |
| tempMotor        | 5 | Int8   | whole °C, direct | **CONFIRMED** (CAN d4, swapped to match Display) |
| driveMode        | 6 | UInt8  | 1 / 2 / 3 | **CONFIRMED** |
| faultCode        | 7 | UInt32 | bitfield | PROVISIONAL — sent 0 |
| flags            | 8 | UInt8  | bit0 run, bit1 brake, bit2 moving, bit3 reverse, bit4 park, bit5 sideStand, bit6 lowBattery, bit7 regen | **CONFIRMED**: run/brake/moving/reverse/park (Gesits d1 + britge + live); **bit5 sideStand = N/A (sensor not wired — firmware forces 0)**; bit6 lowBattery DERIVED (SoC≤15%); **bit7 regen = negative `currentMotor` — works at speed but threshold needs a road ride to finalize** |
| seq              | 9 | UInt32 | frame counter | — |

**Provisional fields are transmitted anyway** (0 or raw) so the wire layout never changes
when their decode is finalized. Don't display them as trustworthy until calibrated.

> **Startup behavior:** fields sourced from **low-rate CAN frames read 0 for the first
> ~1 s after the STM32 boots** until that frame first arrives — notably `batteryPercent`
> (0x10261100 is ~1 Hz). High-rate fields (voltage, temps, flags, mode) populate
> immediately. On the UI, treat `batteryPercent == 0` at startup as "not yet known"
> (show `--` / hold last value) rather than a real 0%.

### Forward-compatibility contract (READ BEFORE EDITING SCHEMA)
Ordinals **@0..@10 are frozen** — never renumber, reorder, retype, or delete them.
New telemetry (odometer, throttle %, BMS, timestamps…) is **appended** at @11, @12, …
Old senders leave new fields at default (0); old readers ignore unknown trailing bytes.
Both sides stay compatible as long as this holds. **Honor `LEN`**, don't assume 40 bytes.

---

## 4. SG2002 reader side

### 4a. Get the C decoder
Reuse the already-generated, vendored files from the STM32 side (MIT-licensed):
`lib/capnp/votol_telemetry.capnp.{c,h}` + the runtime (`capn.c`, `capn-malloc.c`,
`capn-stream.c`, `capnp_c.h`, `capnp_priv.h`, `capn-list.inc`). Or regenerate:
```sh
capnp compile -o/path/to/capnpc-c -I /path/to/c-capnproto/compiler votol_telemetry.capnp
```

### 4b. Decode a CRC-validated payload
```c
#include "capnp_c.h"
#include "votol_telemetry.capnp.h"

/* buf/len = the CRC-checked PAYLOAD only (SYNC/LEN/CRC already stripped) */
struct capn c;
if (capn_init_mem(&c, buf, len, 0 /*unpacked*/) != 0) { /* bad frame, drop */ }

VotolTelemetry_ptr rp;
rp.p = capn_getp(capn_root(&c), 0, 1);
struct VotolTelemetry t;
read_VotolTelemetry(&t, rp);

float volts   = t.batteryVolt / 10.0f;
uint8_t soc   = t.batteryPercent;      /* 0-100 % */
float amps    = t.currentMotor / 10.0f;  /* deci-amps -> A; signed, negative = regen */
uint16_t rpm  = t.rpm;                 /* real motor RPM (already scaled) */
uint16_t kmh  = t.kmh;                 /* provisional */
uint8_t mode  = t.driveMode;           /* 1/2/3 */
int8_t tctrl  = t.tempControl;         /* controller °C */
int8_t tmot   = t.tempMotor;           /* motor °C */
bool running  = t.flags & 0x01;
bool brake    = t.flags & 0x02;
bool moving   = t.flags & 0x04;
bool reverse  = t.flags & 0x08;
bool park     = t.flags & 0x10;
bool sidestand= t.flags & 0x20;
bool lowbat   = t.flags & 0x40;
bool regen    = t.flags & 0x80;        /* regen brake = negative currentMotor (live) */
/* use t.seq to detect dropped frames (should increment by 1) */
```
(C++ `libcapnp` can also read the payload via `capnp::FlatArrayMessageReader`, but the
c-capnproto reader above is the lighter match for the SG2002.)

---

## 5. Reference frame (decoder unit test)

Struct `{batteryVolt=809 (80.9V), batteryPercent=91%, currentMotor=0, rpm=124, kmh=10,
tempControl=49, tempMotor=47, driveMode=3, flags=0x01, seq=12345}` → this exact
**44-byte wire frame** (host round-trip + live-wire verified; layout unchanged by the rename):

```
AA 28 00 00 00 00 04 00 00 00 00 00 00 00 03 00 00 00
29 03 00 00 7C 00 0A 00 31 2F 03 01 00 00 00 00
39 30 00 00 5B 00 00 00 E6 09
```
- `AA` SYNC, `28` LEN=40
- 40-byte payload: stream header `00 00 00 00 04 00 00 00`, root ptr `00 00 00 00 03 00 00 00`,
  then data words (by byte offset): `batteryVolt@0=0x0329=809`, `currentMotor@2=0`,
  `rpm@4=0x007C=124`, `kmh@6=0x0A=10`, `tempControl@8=0x31=49`, `tempMotor@9=0x2F=47`,
  `driveMode@10=3`, `flags@11=1`, `faultCode@12=0`, `seq@16=0x3039=12345`, `batteryPercent@20=0x5B=91`
- `E6 09` = CRC16-CCITT (`0x09E6`) over LEN+payload, little-endian

Feed the 40 payload bytes to your decoder; you must get the values above.

---

## 6. Python cross-check tool (optional)

This reads live frames off a serial port and decodes them — handy to sanity-check the
SG2002 implementation against a known-good reference. Needs `pip install pycapnp pyserial`.

```python
import serial, capnp
capnp.remove_import_hook()
schema = capnp.load('votol_telemetry.capnp')

def crc16(data, crc=0xFFFF):
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc<<1)^0x1021)&0xFFFF if (crc&0x8000) else (crc<<1)&0xFFFF
    return crc

s = serial.Serial('/dev/ttyUSB0', 115200, timeout=1)
buf = bytearray()
while True:
    buf += s.read(256)
    while len(buf) >= 4:
        if buf[0] != 0xAA: buf.pop(0); continue
        L = buf[1]
        if len(buf) < 2+L+2: break
        payload = bytes(buf[2:2+L]); crc_rx = buf[2+L] | (buf[3+L] << 8)
        good = crc_rx == crc16(bytes(buf[1:2+L]))
        del buf[:2+L+2]
        if not good: continue
        with schema.VotolTelemetry.from_bytes(payload) as m:
            print(f"V={m.batteryVolt/10:.1f} SoC={m.batteryPercent}% Icur={m.currentMotor} "
                  f"rpm={m.rpm} kmh={m.kmh} mode={m.driveMode} flags=0x{m.flags:02X} "
                  f"Tctrl={m.tempControl} Tmot={m.tempMotor} seq={m.seq}")
```

Example live output (verified on the bench, 0 CRC failures):
```
V=80.6 SoC=86% rpm=0 kmh=0 mode=1 flags=0x05 Tctrl=47 Tmot=46 seq=2304
```
