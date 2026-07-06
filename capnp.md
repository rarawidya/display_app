# EVDISPLAY Bluetooth Telemetry — App-Facing Protocol Spec

**Audience:** the phone-app developer consuming the board's live telemetry stream.
**Direction:** board → phone (telemetry uplink only; no downlink defined in v1).
**Status:** schema + framing FROZEN and shippable. This document is self-contained —
everything you need to connect, parse frames, and decode Cap'n Proto is here.

The board ("EVdashboard" display unit) broadcasts **all the data the dashboard
currently shows** — the same snapshot the on-screen UI reads — out over Bluetooth,
encoded with **Cap'n Proto**, once per update at ~10 Hz.

---

## 1. Transport: Bluetooth Classic (BR/EDR) — RFCOMM / Serial Port Profile (SPP)

We use **Bluetooth Classic SPP**, not BLE GATT. Rationale: the stream is a
continuous ~10 Hz binary feed of small frames; SPP gives you a reliable, ordered
byte pipe (L2CAP-backed) that maps 1:1 onto "read frames off a socket," which is
by far the simplest thing for both sides. No GATT MTU juggling, no notify/indicate
bookkeeping.

| Property | Value |
|---|---|
| Profile | Serial Port Profile (SPP) over RFCOMM |
| Service UUID | `00001101-0000-1000-8000-00805F9B34FB` (standard SPP UUID) |
| RFCOMM channel | **advertised via SDP** — do NOT hardcode; resolve it from the SDP record via the UUID above. (Current build uses channel **1**, but always prefer SDP lookup.) |
| Device name | `EVdashboard` (Bluetooth adapter friendly name) |
| Role | Board is the **RFCOMM server** (listens/accepts). Phone is the **client** (connects). |
| Pairing | Required. See §6. |
| Payload | length-framed Cap'n Proto messages (see §3–§4) |
| Byte order | little-endian throughout (Cap'n Proto is LE; CRC sent LE) |
| Update rate | ~10 Hz steady (one frame per snapshot publish) |

### Android connect (Kotlin/Java sketch)
```kotlin
val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
val device: BluetoothDevice = /* the paired "EVdashboard" device */
val socket = device.createRfcommSocketToServiceRecord(SPP_UUID) // uses SDP to find the channel
socket.connect()
val input = socket.inputStream   // read frames off this (see §2)
```

If `createRfcommSocketToServiceRecord` ever fails to find the service on a
particular phone, fall back to the reflection channel-1 form
(`createInsecureRfcommSocket(1)`), but the SDP form is the supported path.

---

## 2. On-wire framing

Every telemetry update is sent as ONE self-delimiting frame. The framing is
identical to the board's internal STM32 uplink frame, so the same parser works:

```
+------+------+-------------------------+-------------+
| SYNC | LEN  |   PAYLOAD (LEN bytes)   |  CRC16 (LE) |
+------+------+-------------------------+-------------+
  0xAA   1 B      Cap'n Proto message       2 B
```

- **SYNC** = `0xAA` — frame-start marker. Scan for it to (re)synchronise.
- **LEN** = payload length in bytes, `uint8`. **Always trust LEN**, never hardcode.
  Currently **40**, but it grows if fields are appended to the schema (see §5).
- **PAYLOAD** = one Cap'n Proto `VotolTelemetry` message, **unpacked, single
  segment** (an 8-byte segment/stream header, an 8-byte root pointer, then the
  struct data words — see §4).
- **CRC16** = CRC16-CCITT (poly `0x1021`, init `0xFFFF`, MSB-first, **no** final
  XOR), computed over **LEN + PAYLOAD** (i.e. the LEN byte followed by the payload
  bytes), transmitted **little-endian** (low byte first).

Total frame length = `LEN + 4` bytes.

### Reader algorithm (do this on the phone)
1. Read bytes until you see `0xAA`.
2. Read `LEN` (1 byte).
3. Read `LEN` payload bytes.
4. Read 2 CRC bytes (little-endian → `crc_rx = b[0] | (b[1] << 8)`).
5. Recompute CRC16 over `LEN || payload`. If mismatch, **discard and go back to
   step 1** — a `0xAA` can legitimately occur inside the payload; a failed CRC
   just means keep scanning and the next real frame resyncs.
6. On match, decode the payload (§4).

SPP is a reliable stream, so in practice CRC failures should be ~0; the CRC + resync
exist so a mid-stream connect (joining between frame boundaries) self-heals.

### CRC16-CCITT reference (C)
```c
uint16_t crc16_ccitt(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++)
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021)
                                 : (uint16_t)(crc << 1);
    }
    return crc; // compare against the 2 received bytes read little-endian
}
```
### CRC16-CCITT reference (Kotlin)
```kotlin
fun crc16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (byte in data) {
        crc = crc xor ((byte.toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
    }
    return crc
}
```

---

## 3. Cap'n Proto schema (`votol_telemetry.capnp`)

The payload is a serialized `VotolTelemetry` struct. Load this schema with your
Cap'n Proto tooling (pycapnp, capnproto-java, etc.). **File ID and field ordinals
are frozen** — see §5.

```capnp
@0xf0e5f2ff4f178d2a;

struct VotolTelemetry {
  batteryDeciVolts @0 :UInt16;   # battery voltage, 0.1 V units (809 = 80.9 V) — CONFIRMED
  motorCurrentRaw  @1 :Int16;    # motor current — TODO, location unknown, currently sent 0
  rpm              @2 :UInt16;   # motor rpm / speed proxy — PROVISIONAL
  speedKmh         @3 :UInt16;   # km/h (derived: rpm * 83 / 1000) — PROVISIONAL
  controllerTempC  @4 :Int8;     # controller temp, whole deg C, direct — CANDIDATE
  motorTempC       @5 :Int8;     # motor temp, whole deg C, direct — CONFIRMED
  driveMode        @6 :UInt8;    # 1 / 2 / 3 — CONFIRMED
  faultCode        @7 :UInt32;   # controller fault bitfield — PROVISIONAL, currently 0
  flags            @8 :UInt8;    # bitfield, see below — CONFIRMED
  seq              @9 :UInt32;   # rolling frame counter (drop detection)
  batteryPercent   @10 :UInt8;   # state-of-charge, DIRECT 0-100 % (255 = not-yet-known) — CONFIRMED
}
```

### Field meaning / units / trust
| Field | @ | Type | Units / meaning | Status |
|-------|---|------|-----------------|--------|
| batteryDeciVolts | 0 | UInt16 | 0.1 V (÷10 → volts) | **CONFIRMED** |
| motorCurrentRaw  | 1 | Int16  | raw counts | TODO — sent 0 |
| rpm              | 2 | UInt16 | rpm / speed proxy | **PROVISIONAL** — calibrate vs speedo |
| speedKmh         | 3 | UInt16 | km/h (`rpm*83/1000`) | **PROVISIONAL** |
| controllerTempC  | 4 | Int8   | whole °C, direct | CANDIDATE |
| motorTempC       | 5 | Int8   | whole °C, direct | **CONFIRMED** |
| driveMode        | 6 | UInt8  | 1 / 2 / 3 | **CONFIRMED** |
| faultCode        | 7 | UInt32 | bitfield | PROVISIONAL — sent 0 |
| flags            | 8 | UInt8  | see bit table below | **CONFIRMED** |
| seq              | 9 | UInt32 | frame counter | — |
| batteryPercent   | 10 | UInt8 | 0-100 % SoC, or **255 = not yet known** | **CONFIRMED** |

### `flags` bit table (UInt8)
| Bit | Mask | Meaning |
|-----|------|---------|
| 0 | 0x01 | engineRunning (1 = started/running) |
| 1 | 0x02 | brake |
| 2 | 0x04 | moving |
| 3 | 0x08 | reverse gear engaged |
| 4-7 | — | reserved (0) |

### Values you should treat specially
- **`batteryPercent == 255`** → SoC not yet known (comes from a ~1 Hz CAN frame that
  is silent for ~1 s after the vehicle MCU boots). Show `--` / hold last value, do
  NOT display as 0%.
- **Provisional / TODO fields** (`motorCurrentRaw`, `rpm`, `speedKmh`, `faultCode`,
  `controllerTempC`) are transmitted anyway so the wire layout is stable, but don't
  present them as trustworthy until calibration lands. `speedKmh`/`rpm` are roughly
  linear with real speed but the scale factor is not yet road-calibrated.
- Use **`seq`** (should increment by 1 per frame) to detect dropped frames.

---

## 4. Payload layout (for a hand-rolled decoder)

If you use a real Cap'n Proto library, ignore this section — just feed the `LEN`
payload bytes to e.g. `VotolTelemetry.from_bytes(payload)` (pycapnp) or
`SerializePacked`/flat reader for your language (the message is **unpacked**, so use
the flat/unpacked reader, NOT the packed one).

If you decode by hand: the 40-byte payload is:
- bytes `0..7`: segment/stream header (`00 00 00 00 04 00 00 00` = 1 segment, 4 words)
- bytes `8..15`: struct root pointer (`00 00 00 00 03 00 00 00` = 3 data words, 0 ptrs)
- bytes `16..39`: the 3 data words (24 bytes), little-endian, at these offsets:

| Field | Byte offset (within payload) | Size | Type |
|-------|------------------------------|------|------|
| batteryDeciVolts | 16 | 2 | u16 LE |
| motorCurrentRaw  | 18 | 2 | i16 LE |
| rpm              | 20 | 2 | u16 LE |
| speedKmh         | 22 | 2 | u16 LE |
| controllerTempC  | 24 | 1 | i8 |
| motorTempC       | 25 | 1 | i8 |
| driveMode        | 26 | 1 | u8 |
| flags            | 27 | 1 | u8 |
| faultCode        | 28 | 4 | u32 LE |
| seq              | 32 | 4 | u32 LE |
| batteryPercent   | 36 | 1 | u8 |
| (padding)        | 37..39 | 3 | 0 |

(Using a proper Cap'n Proto reader is strongly recommended — it stays correct if the
struct grows per §5.)

---

## 5. Forward-compatibility contract (READ BEFORE relying on fixed offsets)

- File ID `@0xf0e5f2ff4f178d2a` and ordinals **@0..@10 are FROZEN** — never
  renumbered, reordered, retyped, or deleted.
- New telemetry (odometer, throttle %, BMS/per-cell, timestamps, turn signals…)
  will be **appended** at `@11, @12, …`. This makes `LEN` grow.
- **Therefore: honor `LEN`; do not assume 40 bytes.** A proper Cap'n Proto reader
  ignores unknown trailing bytes automatically, so old app versions keep working
  against a newer board. If you hand-decode fixed offsets, guard on `LEN`.

---

## 6. Reference frame (decoder self-test)

Struct `{batteryDeciVolts=809, batteryPercent=91, rpm=124, speedKmh=10,
controllerTempC=49, motorTempC=47, driveMode=3, flags=0x01, seq=12345,
motorCurrentRaw=0, faultCode=0}` serializes to this exact **44-byte wire frame**:

```
AA 28 00 00 00 00 04 00 00 00 00 00 00 00 03 00 00 00
29 03 00 00 7C 00 0A 00 31 2F 03 01 00 00 00 00
39 30 00 00 5B 00 00 00 E6 09
```
- `AA` SYNC, `28` LEN=40
- 40-byte payload as laid out in §4
- `E6 09` = CRC16-CCITT `0x09E6` over LEN+payload, little-endian

Feed the 40 payload bytes to your Cap'n Proto reader → you must get the values above.
This is a great app-side unit test with no board required.

---

## 7. Pairing & connecting (end-to-end)

1. On the phone, enable Bluetooth and **pair with `EVdashboard`**.
   - The board uses SSP Just-Works / legacy PIN. Modern phones get an **SSP
     confirm dialog** (tap Pair/Confirm). Older stacks may prompt for PIN **1234**.
2. Open an SPP/RFCOMM connection to the SPP UUID
   `00001101-0000-1000-8000-00805F9B34FB` (resolve channel via SDP).
3. Read the byte stream and parse frames per §2.
4. For each CRC-valid frame, decode the Cap'n Proto payload per §3/§4 and update
   your UI. Expect ~10 frames/second. If frames stop arriving (or `seq` stalls),
   treat the link as stale.

---

## 8. Update rate & liveness

- Nominal **~10 Hz**. The board emits one frame per telemetry-snapshot publish.
- If the vehicle MCU link is down, the board still emits frames but with stale
  data; watch `seq` (frozen counter ⇒ no fresh vehicle data) and/or a receive
  timeout on your side to show "no link".
- On first connect you may join mid-frame; the `0xAA` + CRC resync (§2) handles it.

---

## Changelog
- **v1 (2026-07-03):** initial app-facing spec. SPP transport, `[0xAA][LEN][capnp][CRC16-LE]`
  framing, `VotolTelemetry` schema `@0..@10`, ~10 Hz.
