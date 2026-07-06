# EVDISPLAY — BLE Communication Spec (App ↔ Dashboard)

**SOURCE OF TRUTH for the mobile app team.** Self-contained: covers both the BLE
**transport** and the telemetry **payload/decode**. Supersedes the old Classic-SPP
docs (`PROTOCOL.md`, `DOWNLINK.md`) and the interim `BLE-TRANSPORT.md`. Reconciled
with `BLE_FIRMWARE_REQUIREMENTS.md` (the field-by-field checklist form of this).

| | |
|---|---|
| Board | Sophgo **SG2002** dashboard + **AICSemi AIC8800D80** (BLE 5.x) |
| Firmware | `ble-gatt-server` md5 `ddcc10ff…`, 2026-07-06 |
| Spec version / date | 1.0 · 2026-07-06 |
| Role | Board = **BLE peripheral / GATT server**; phone = **central** |

---

## 1. Transport & device identity

- **BLE only** (not Bluetooth Classic). GATT peripheral.
- **Device name:** `EVdisplay` (Complete Local Name, in the **scan response**).
- **Address:** **PUBLIC, stable** (per-unit, e.g. `4C:A3:8F:14:D9:57`). Reconnect by
  stored MAC **or** re-scan by service UUID — both work. No RPA rotation.
- **Advertises:** 16-bit service UUID **`0xAF00`** (AD type 0x03) → **ScanFilter on
  `0xAF00`**. Connectable (ADV_IND), ~continuous while powered, interval 100–150 ms.
- **Security: NONE — open GATT, no bonding/pairing.** The app connects, discovers,
  subscribes with **no PIN and no pairing dialog**. If a phone ever prompts for a
  PIN it is talking to a stale Classic bond — *forget the device and reconnect via
  the app/scanner*, never via the system “pair” flow.
- **iOS is supported** now (CoreBluetooth) — unlike the retired Classic SPP.

> In **nRF Connect**, `0xAF00`/`0xAF08`/`0xAF07` show as “Unknown Service/Characteristic”
> (they are vendor 16-bit UUIDs) — that is expected.

---

## 2. GATT profile (as shipped)

128-bit forms follow the Bluetooth base UUID `0000xxxx-0000-1000-8000-00805f9b34fb`.

| Service | Characteristic | UUID (16-bit / 128-bit) | Properties | Descriptor | Purpose |
|---|---|---|---|---|---|
| **0xAF00** (`0000af00-…`) | **Telemetry TX** | `0xAF08` / `0000af08-0000-1000-8000-00805f9b34fb` | **Notify** | **CCCD `0x2902`** | board → phone telemetry (subscribe to receive) |
| **0xAF00** | **Command RX** | `0xAF07` / `0000af07-0000-1000-8000-00805f9b34fb` | **Write**, **Write-No-Response** | — | phone → board (v1 app is receive-only; may ignore) |
| `0x1800` (GAP) | Device Name | `0x2A00` | Read | — | "EVdisplay" |

To receive telemetry: **subscribe to `0xAF08`** = write `0x0001` to its CCCD `0x2902`.
Streaming begins immediately (no start command). Unsubscribe = write `0x0000`.

---

## 3. Connection requirements

1. **Request MTU 247 on connect — REQUIRED.** `requestMtu(247)` on Android; iOS
   auto-negotiates (~185). The board grants up to 247 and also sends a
   *server-initiated* MTU request as a backstop. **Why:** a telemetry frame is
   **44 bytes**; it fits in one notification only when **ATT_MTU ≥ 47**.
   - **Fallback:** if the central stays at the default MTU 23, the board **chunks**
     the frame into ≤20-byte notifications. Feed the notification bytes to the
     **byte-stream FrameDecoder** (§4) which reassembles across notifications — so
     it still works, but *raise the MTU* for one-notification-per-frame.
2. **One central at a time.** A second connection is not served.
3. Connection interval ~7.5–30 ms is ideal for 10 Hz (accepts the central’s params).
4. **Auto-re-advertises on disconnect** — the device reliably reappears; reconnect
   by MAC or re-scan `0xAF00`.

---

## 4. Framing on 0xAF08

Each notification value (at MTU ≥ 47) is **one complete frame**:

```
+------+------+---------------------------+---------------+
| SYNC | LEN  |   PAYLOAD (LEN bytes)     |  CRC16 (LE)   |
+------+------+---------------------------+---------------+
  0xAA   1 B      Cap'n Proto message          2 B
```

- `SYNC` = `0xAA`. `LEN` = payload length (telemetry payload is **40**, so LEN=`0x28`).
- `CRC16` = **CRC16-CCITT** (poly `0x1021`, init `0xFFFF`, MSB-first, **no** final XOR)
  computed over **`LEN` byte ‖ payload**, transmitted **little-endian** (low byte first).
- Total frame = LEN + 4 = **44 bytes** for telemetry.

### Reader algorithm (byte stream; handles MTU-247 single frames AND MTU-23 chunks)
```
accumulate incoming notification bytes into a buffer
loop:
  drop bytes until buffer[0] == 0xAA
  if fewer than 2 bytes: wait for more
  LEN = buffer[1]
  if fewer than LEN+4 bytes: wait for more
  payload = buffer[2 .. 2+LEN]
  crc_rx  = buffer[2+LEN] | (buffer[3+LEN] << 8)
  if crc16(buffer[1 .. 2+LEN]) == crc_rx:  decode payload (§5)
  else: resync (advance one byte, look for next 0xAA)
  remove LEN+4 bytes from buffer
```

### CRC16 reference — C
```c
uint16_t crc16_ccitt(const uint8_t *d, size_t n){
    uint16_t crc=0xFFFF;
    for(size_t i=0;i<n;i++){ crc^=(uint16_t)d[i]<<8;
        for(int b=0;b<8;b++) crc=(crc&0x8000)?(crc<<1)^0x1021:(crc<<1); }
    return crc;               /* call over LEN || payload */
}
```
### CRC16 reference — Kotlin
```kotlin
fun crc16(data: ByteArray): Int {          // pass LEN||payload
    var crc = 0xFFFF
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xFF) shl 8)
        repeat(8){ crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xFFFF
    }
    return crc
}
```

---

## 5. Payload — VotolTelemetry (STM32G4 schema, Cap'n Proto)

The payload is the **exact same 40-byte Cap'n Proto message the STM32G4 emits** —
unpacked, single segment, **file id `0xf0e5f2ff4f178d2a`**. (Verified field-by-field
identical STM32 → board → BLE.) Decode with any capnp library, or by hand (§5c).

### 5a. Schema
```capnp
@0xf0e5f2ff4f178d2a;
struct VotolTelemetry {
  batteryDeciVolts @0  :UInt16;   # battery voltage, 0.1 V units (812 = 81.2 V)
  motorCurrentRaw  @1  :Int16;    # raw motor current (see trust note)
  rpm              @2  :UInt16;   # motor rpm / eRPM proxy (provisional)
  speedKmh         @3  :UInt16;   # km/h (provisional; derived from rpm)
  controllerTempC  @4  :Int8;     # controller temperature, whole °C
  motorTempC       @5  :Int8;     # motor temperature, whole °C
  driveMode        @6  :UInt8;    # 1 / 2 / 3
  faultCode        @7  :UInt32;   # controller fault bitfield (provisional)
  flags            @8  :UInt8;    # status bits (see 5b)
  seq              @9  :UInt32;   # rolling frame counter (drop detection)
  batteryPercent   @10 :UInt8;    # state of charge, 0–100 %
}
```

### 5b. Field meaning / units / trust
| Ord | Field | Type | Units / meaning | Trust |
|---|---|---|---|---|
| @0 | batteryDeciVolts | UInt16 | 0.1 V (÷10 → volts) | Confirmed on bike |
| @1 | motorCurrentRaw | Int16 | raw; scale TBD | **Provisional** — often 0 until road-calibrated; also drives `regen` flag |
| @2 | rpm | UInt16 | rpm/eRPM proxy | Provisional (linear w/ speed) |
| @3 | speedKmh | UInt16 | km/h | Provisional (rpm·0.083) — calibrate vs speedo |
| @4 | controllerTempC | Int8 | °C | Candidate |
| @5 | motorTempC | Int8 | °C | Confirmed |
| @6 | driveMode | UInt8 | 1/2/3 | Confirmed |
| @7 | faultCode | UInt32 | fault bitfield | **Provisional** — 0 for now |
| @8 | flags | UInt8 | see bit table | Mixed (per-bit) |
| @9 | seq | UInt32 | frame counter | Confirmed (see §6) |
| @10 | batteryPercent | UInt8 | 0–100 % | Confirmed (~1 Hz source) |

### 5b. `flags` bit table (UInt8)
| Bit | Name | Meaning |
|---|---|---|
| 0 | engineRunning | 1 = started/running (0 = park) |
| 1 | brake | brake applied |
| 2 | moving | vehicle moving |
| 3 | reverse | reverse selected |
| 4 | park | park (== !engineRunning) |
| 5 | sideStand | kickstand (under test) |
| 6 | lowBattery | derived: batteryPercent ≤ 15 % |
| 7 | regen | regen braking (motorCurrentRaw < −thr; 0 until @1 mapped) |

### 5c. Byte-offset layout (for a hand-decoder)
Payload = 8-byte segment header + 8-byte root pointer + **24-byte struct**. All
little-endian. Struct-data byte offsets (add 16 for the offset within the payload):

| Field | struct byte | payload byte | size |
|---|---|---|---|
| batteryDeciVolts @0 | 0 | 16 | u16 |
| motorCurrentRaw @1 | 2 | 18 | i16 |
| rpm @2 | 4 | 20 | u16 |
| speedKmh @3 | 6 | 22 | u16 |
| controllerTempC @4 | 8 | 24 | i8 |
| motorTempC @5 | 9 | 25 | i8 |
| driveMode @6 | 10 | 26 | u8 |
| flags @8 | 11 | 27 | u8 |
| faultCode @7 | 12 | 28 | u32 |
| seq @9 | 16 | 32 | u32 |
| batteryPercent @10 | 20 | 36 | u8 |

(The first 8 payload bytes are `00 00 00 00 04 00 00 00`; the next 8 are the root
pointer `00 00 00 00 03 00 00 00` — constant for this schema.)

### 5d. Values to treat specially
- `batteryPercent == 255` → **"not yet known"** (its ~1 Hz CAN source reads 0 for
  ~1 s after the STM32 boots; the board publishes 255 meanwhile). Don’t show 255 %.
- **All-zero payload** → the STM32 is unpowered / no vehicle data yet (the frame is
  still valid; values populate once the STM32 is live).
- Provisional fields (@1 motorCurrentRaw, @7 faultCode, and the scale of @2/@3) may
  read 0 or need road-ride calibration — display defensively.

### 5e. Golden reference frame (offline unit test)
Values `V=809 (81.9 V), motorCurrentRaw=0, rpm=124, speedKmh=10, Tctrl=49, Tmot=47,
mode=3, flags=0x01, faultCode=0, seq=12345, batteryPercent=91` encode to this
**exact 44-byte 0xAF08 notification** (LEN=`0x28`, CRC16=`0x09E6`):
```
AA 28 00 00 00 00 04 00 00 00 00 00 00 00 03 00 00 00 29 03 00 00 7C 00 0A 00
31 2F 03 01 00 00 00 00 39 30 00 00 5B 00 00 00 E6 09
```
Your decoder must recover exactly those 11 values.

---

## 6. seq semantics & rate

- **`seq` is the STM32’s own rolling counter, passed through unchanged** to BLE (not
  a BLE-local counter). Use it for **drop / gap detection**.
- The STM32 emits at **~20 Hz** over its serial link; the board **re-samples the
  latest snapshot and notifies at ~10 Hz**. So the app receives the **freshest**
  frame at ~10 Hz and `seq` typically increments by ~2 between BLE notifications —
  this is expected (no field is lost; you just get every other STM32 frame).
- `batteryPercent` updates from a **~1 Hz** vehicle source (a source property, not a
  BLE limit) — it changes slower than the other fields.

---

## 7. Android integration sketch (BluetoothGatt)
```kotlin
val SVC  = UUID.fromString("0000af00-0000-1000-8000-00805f9b34fb")
val TX   = UUID.fromString("0000af08-0000-1000-8000-00805f9b34fb") // notify
val RX   = UUID.fromString("0000af07-0000-1000-8000-00805f9b34fb") // write (v1: unused)
val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// scan with ScanFilter(ServiceUuid = SVC) or name "EVdisplay", then:
device.connectGatt(ctx, false, object : BluetoothGattCallback() {
  override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
    if (ns == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247)      // REQUIRED
  }
  override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }
  override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
    val tx = g.getService(SVC).getCharacteristic(TX)
    g.setCharacteristicNotification(tx, true)
    val cccd = tx.getDescriptor(CCCD)
    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE   // 0x01 0x00
    g.writeDescriptor(cccd)                                          // -> stream starts
  }
  override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
    if (c.uuid == TX) frameDecoder.feed(c.value)   // byte-stream FrameDecoder (§4)
  }                                                //   -> VotolTelemetry (§5)
})
```

## 8. iOS integration sketch (CoreBluetooth)
```swift
let SVC = CBUUID(string: "AF00")           // 16-bit is fine on iOS
let TX  = CBUUID(string: "AF08")           // notify
let RX  = CBUUID(string: "AF07")           // write (v1: unused)

central.scanForPeripherals(withServices: [SVC])        // scan-filter on 0xAF00
// didDiscover -> central.connect(peripheral)
// didConnect  -> peripheral.discoverServices([SVC])    (iOS negotiates MTU itself)
func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
  for c in s.characteristics ?? [] where c.uuid == TX { p.setNotifyValue(true, for: c) }
}
func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
  if c.uuid == TX, let d = c.value { frameDecoder.feed(d) }   // -> VotolTelemetry
}
```
(iOS does not expose an explicit requestMtu — it auto-negotiates ~185, which is ≥ 47,
so telemetry arrives as one notification per frame.)

---

## 9. Error handling, edge cases & quirks
- **Open GATT, no auth** — never expect/att a pairing or encryption prompt. A PIN
  prompt means a **stale Classic bond** on the phone: *Forget* “EVdisplay” in system
  settings, then connect via the app/scanner (LE), not via system pairing.
- **requestMtu(247) is mandatory** for one-notification-per-frame (Android). Without
  it you get 20-byte chunks — still decodable via the byte-stream FrameDecoder.
- **GATT cache:** if the board’s GATT layout ever changes, Android/iOS may serve a
  stale cached profile → toggle the phone’s Bluetooth off/on (or nRF “Refresh
  services”) to force fresh discovery.
- **Reconnect:** by stored MAC (public/stable) or re-scan `0xAF00`. Board
  auto-re-advertises after every disconnect.
- **One central at a time.**
- **Do not use the dashboard’s on-screen Bluetooth toggle to “fix” connectivity** —
  it is a legacy control; the board auto-advertises on its own.
- ATT status codes you may see: `0x0A` (Attribute Not Found — normal discovery
  termination), `0x06` (Request Not Supported), `0x02` (reading the write-only RX).

---

## 10. Command RX (0xAF07) — future, optional
The app v1 is receive-only and may ignore this. If used later: write a framed
`PhoneNotification` (`[0xAA][LEN][capnp][CRC16-LE]`, capnp file id
`0xb39c7a21e4d05f68`) to `0xAF07`; the board publishes it to the dashboard UI.

---
*Version 1.0 · 2026-07-06 · SG2002 + AIC8800D80 · ble-gatt-server md5 ddcc10ff.
Every value verified against the shipped firmware.*
