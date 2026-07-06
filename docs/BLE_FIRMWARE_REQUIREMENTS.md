# BLE Interface Specification — Controller ↔ Mobile App

**Owner:** Firmware team (fills this in) · **Consumer:** Mobile app team (implements from it)

This document is the **single source of truth** for how the Android/iOS app connects
to the motorcycle controller over **Bluetooth Low Energy (BLE / GATT)**. The
**firmware team completes every field** to match the controller's actual BLE
implementation; the **mobile team implements strictly against this document**.

> Legend: **`____`** = value to be filled in · **☐** = check the option that applies ·
> _(observed …)_ = value seen by the app's on-device probe, **to be confirmed by firmware**.
>
> The telemetry **payload/framing** (Cap'n Proto message + `[0xAA][LEN][…][CRC16]`) is
> defined separately in `capnp.md` and is unchanged by BLE. This document covers only
> the **BLE transport** that carries it.

---

## 0. Document control

| Field | Value |
|---|---|
| Firmware version this spec describes | `____` |
| Hardware / board revision | `____` |
| Author (firmware) | `____` |
| Date completed | `____` |
| Spec version | `____` |
| Reviewed by (mobile) | `____` |

**Change log**

| Date | FW ver | Change |
|---|---|---|
| `____` | `____` | initial |

---

## 1. Device identity & advertising

| # | Item | Value |
|---|------|-------|
| 1.1 | Advertised device name (Complete/Shortened Local Name) | `____` _(observed: none / "(unnamed)")_ |
| 1.2 | Is a name present in the advertisement or scan response? | ☐ Adv ☐ Scan-response ☐ None |
| 1.3 | **Service UUID(s) advertised** (for scan filtering) | `____` _(observed: not advertised; primary service is `0xAF00`)_ |
| 1.4 | Manufacturer-specific data present? (company ID + payload) | ☐ No ☐ Yes → `____` |
| 1.5 | Service data present? | ☐ No ☐ Yes → `____` |
| 1.6 | Advertising interval (min / max) | `____` ms |
| 1.7 | Advertising is connectable? | ☐ Yes (required) ☐ No |
| 1.8 | TX power level (advertised / dBm) | `____` |
| 1.9 | Does the controller advertise continuously while awake, or only in a pairing window? | ☐ Continuous ☐ Window → duration `____` / trigger `____` |

> **Mobile need:** to discover the controller quickly and cheaply, the app scans with
> a **`ScanFilter` on the advertised service UUID** (1.3). If the service UUID is not
> advertised, the app must fall back to name (1.1) or an unfiltered scan (slower, more
> power). Please advertise the primary service UUID.

---

## 2. Address type & privacy

| # | Item | Value |
|---|------|-------|
| 2.1 | **BLE address type** | ☐ Public ☐ Random-static ☐ **Random-resolvable (RPA, rotating)** ☐ Random-non-resolvable — _(observed: **RPA / rotating**)_ |
| 2.2 | If RPA: rotation interval | `____` (typ. 15 min) |
| 2.3 | Does the address persist across power cycles / reboots? | ☐ Yes ☐ No |
| 2.4 | If RPA: is an IRK provided at bonding so the central can resolve/track it? | ☐ Yes (via bond) ☐ No |

> **Mobile impact (critical for reconnect):** with a **rotating RPA and no bond**, the
> saved MAC address becomes invalid, so the app **must reconnect by re-scanning for the
> service UUID (1.3)** each time rather than connecting to a stored address.
> A **public or random-static** address (or **bonding**, which lets the OS resolve the
> RPA to a stable identity) makes reconnection faster and simpler. **Firmware: please
> state which you use and, if possible, prefer a stable/resolvable option.**

---

## 3. Security & pairing

| # | Item | Value |
|---|------|-------|
| 3.1 | Is bonding/pairing **required** to read/subscribe telemetry? | ☐ No — "just works", unencrypted _(observed: NONE)_ ☐ Yes |
| 3.2 | Pairing method | ☐ Just Works ☐ Passkey entry ☐ Numeric comparison ☐ OOB |
| 3.3 | IO capabilities | `____` |
| 3.4 | Which characteristics require encryption / authentication? | `____` (or "none") |
| 3.5 | Is a bond persisted on the controller across reboots? | ☐ Yes ☐ No |
| 3.6 | Max simultaneous bonded centrals | `____` |

---

## 4. Connection parameters

| # | Item | Value |
|---|------|-------|
| 4.1 | Preferred connection interval (min / max) | `____` ms |
| 4.2 | Slave latency | `____` |
| 4.3 | Supervision timeout | `____` ms |
| 4.4 | **Max ATT MTU supported** | `____` B _(observed: **256**)_ |
| 4.5 | Data Length Extension supported? | ☐ Yes ☐ No |
| 4.6 | PHY supported | ☐ 1M ☐ 2M ☐ Coded |
| 4.7 | Max simultaneous central connections | `____` |
| 4.8 | Does the controller renegotiate/limit conn params after connect? | ☐ No ☐ Yes → `____` |

> **Mobile need:** telemetry runs ~10 Hz; a **connection interval of ~7.5–30 ms** and
> **MTU ≥ 185** (247 ideal) let one frame fit in a single notification. The app requests
> a larger MTU on connect — please honor it.

---

## 5. GATT profile (services & characteristics)

**5.1 Full GATT table** — firmware lists **every** service/characteristic the app may
encounter (mark the telemetry ones clearly):

| Service UUID | Characteristic UUID | Properties | Descriptors | Purpose |
|---|---|---|---|---|
| `____` | `____` | R / W / WNR / Notify / Indicate | CCCD? | `____` |
| `____` | `____` | | | |

_(Observed profile — firmware to confirm/replace:)_

| Service | Characteristic | Properties | Purpose (observed) |
|---|---|---|---|
| `0xAF00` (`0000af00-…`) | `0xAF08` (`0000af08-…`) | **NOTIFY** + CCCD | **Telemetry TX (board→phone)** — confirm |
| `0xAF00` | `0xAF07` (`0000af07-…`) | WRITE_NO_RESPONSE | Command RX (phone→board) — confirm |
| `0x1800` | `0x2A00` | READ | Generic Access – device name (standard) |
| `0xFE2C` | `fe2c1233..1237` | R / W / Notify | Google Fast Pair — **not telemetry, ignore** |

### 5.2 Telemetry TX characteristic (REQUIRED)

| # | Item | Value |
|---|------|-------|
| 5.2.1 | Service UUID | `____` _(observed `0xAF00`)_ |
| 5.2.2 | Characteristic UUID | `____` _(observed `0xAF08`)_ |
| 5.2.3 | Delivery | ☐ Notify ☐ Indicate — _(observed: Notify)_ |
| 5.2.4 | Has CCCD `0x2902`? | ☐ Yes (required) |
| 5.2.5 | Does the app need to write anything to **start** the stream, beyond enabling the CCCD? | ☐ No, streams on subscribe ☐ Yes → command `____` |

> **Mobile need:** Notify preferred (no per-packet ACK); telemetry must begin once the
> app writes the CCCD to enable notifications (5.2.5).

### 5.3 Command RX characteristic (OPTIONAL / future)

| # | Item | Value |
|---|------|-------|
| 5.3.1 | Present? | ☐ No ☐ Yes _(observed: `0xAF07`)_ |
| 5.3.2 | Service / Characteristic UUID | `____` / `____` |
| 5.3.3 | Write type | ☐ Write ☐ Write-No-Response |
| 5.3.4 | Command payload format | `____` (or "TBD") |

> The current app is **receive-only** and will not write commands in v1. Documenting
> this now avoids a second round-trip later.

### 5.4 Other services (battery, DFU/OTA, device info, config)

| # | Item | Value |
|---|------|-------|
| 5.4.1 | Device Information Service `0x180A` present? (firmware rev, serial) | ☐ No ☐ Yes → chars `____` |
| 5.4.2 | Battery Service `0x180F`? | ☐ No ☐ Yes |
| 5.4.3 | DFU / OTA service? | ☐ No ☐ Yes → `____` |
| 5.4.4 | Any config/settings service the app should know about? | `____` |

---

## 6. Telemetry data format over GATT

| # | Item | Value |
|---|------|-------|
| 6.1 | **Framing on the characteristic value** | ☐ Full `[0xAA][LEN][payload][CRC16-LE]` frame(s) ☐ One complete frame per notification ☐ Raw payload, no sync/CRC |
| 6.2 | Can a single frame span multiple notifications? | ☐ No (1 notif = 1 frame) ☐ Yes (byte stream) |
| 6.3 | Payload definition | Cap'n Proto `VotolTelemetry` per `capnp.md` §3–§4 (unchanged) |
| 6.4 | Byte order | Little-endian (per `capnp.md`) |
| 6.5 | CRC | ☐ CRC16-CCITT over `LEN‖payload`, sent LE (per `capnp.md`) ☐ None over GATT |
| 6.6 | Update rate | `____` Hz _(target ~10 Hz)_ |
| 6.7 | Are fields/scaling identical to the SPP build? | ☐ Yes ☐ No → `____` |

> Reference framing (unchanged from SPP):
> ```
> +------+------+-------------------------+-------------+
> | SYNC | LEN  |   PAYLOAD (LEN bytes)   |  CRC16 (LE) |
> +------+------+-------------------------+-------------+
>   0xAA   1 B      Cap'n Proto message       2 B
> ```

---

## 7. Connection lifecycle & behavior

| # | Item | Value |
|---|------|-------|
| 7.1 | When does telemetry start streaming? | ☐ On CCCD subscribe ☐ On connect ☐ After a start command `____` |
| 7.2 | Behavior when the app unsubscribes / disconnects | `____` |
| 7.3 | Idle/keep-alive: does the controller disconnect an idle central? | ☐ No ☐ Yes → after `____` |
| 7.4 | Multiple centrals: what if a second phone connects? | ☐ Rejected ☐ Allowed ☐ Replaces first |
| 7.5 | On controller reset / power-cycle: does it re-advertise automatically? | ☐ Yes ☐ No |
| 7.6 | Behavior in sleep/standby (see §8) re: advertising & connection | `____` |

---

## 8. Power states

| State | Advertises? | Accepts connection? | Streams telemetry? | Notes |
|---|---|---|---|---|
| Active / running | ☐ | ☐ | ☐ | |
| Idle / key-on-parked | ☐ | ☐ | ☐ | |
| Sleep / standby | ☐ | ☐ | ☐ | wake trigger: `____` |
| Off / charging | ☐ | ☐ | ☐ | |

> **Mobile need:** the app's auto-reconnect strategy depends on when the controller is
> discoverable/connectable. Describe how BLE behaves in each state.

---

## 9. Reconnection strategy (firmware guidance to mobile)

| # | Item | Value / guidance |
|---|------|------------------|
| 9.1 | Recommended reconnect method | ☐ Reconnect by stored address ☐ **Re-scan by service UUID** ☐ Directed advertising after bond |
| 9.2 | Does the controller use directed advertising / whitelist toward a bonded central? | ☐ No ☐ Yes |
| 9.3 | Expected time from power-on to advertising | `____` |
| 9.4 | Any de-dup / identity beyond the (possibly rotating) address (e.g., serial in DIS, name suffix)? | `____` |

> With the observed **rotating RPA + no bond**, the app will default to **9.1 = re-scan
> by service UUID**. Confirm or provide a stable-identity alternative.

---

## 10. Error handling & edge cases

| # | Item | Value |
|---|------|-------|
| 10.1 | GATT error/status codes the controller may return, and meaning | `____` |
| 10.2 | Behavior on malformed/oversized central writes | `____` |
| 10.3 | Notification backpressure: what if the central is slow? (drop / buffer / disconnect) | `____` |
| 10.4 | Max notification rate the stack sustains | `____` |
| 10.5 | Known firmware quirks the app should work around | `____` |

---

## 11. Acceptance checklist (mobile verifies against firmware)

- [ ] Controller advertises with the documented **name** and **service UUID** (§1).
- [ ] **Address type** and **bonding** behave as documented (§2, §3).
- [ ] **Telemetry TX** characteristic present with **Notify/Indicate + CCCD** (§5.2).
- [ ] Enabling the CCCD starts a ~10 Hz stream of **CRC-valid** frames (§6).
- [ ] **MTU ≥ 185** negotiated (§4.4).
- [ ] Reconnect works after link drop / power-cycle per the documented method (§9).
- [ ] Power-state behavior matches §8.
- [ ] (If applicable) RX write characteristic present and format documented (§5.3).

> The app ships a **BLE GATT probe** (Developer options → *BLE GATT probe*) that scans,
> connects, dumps the full GATT table, and can **Listen** on a notify characteristic —
> running the bytes through the production `FrameDecoder` to confirm valid telemetry.
> Its output is the fastest way to sign off items above.

---

## Appendix A — Probe snapshot (2026-07-06, informational)

Raw on-device probe of the current controller (values **to be confirmed by firmware**
in the sections above):

```
Device:  (unnamed)  [54:84:50:22:89:D8]
Type:    UNKNOWN   Bond: NONE   Addr: random-resolvable (rotates)
Negotiated MTU: 256  (ATT payload 253 B)
SERVICE 0000af00-0000-1000-8000-00805f9b34fb
   • 0000af07-…   [WRITE_NR]
   • 0000af08-…   [NOTIFY]  +CCCD          ← telemetry TX candidate
SERVICE 00001800-0000-1000-8000-00805f9b34fb   (Generic Access)
   • 00002a00-…   [READ]                    (device name)
SERVICE 0000fe2c-0000-1000-8000-00805f9b34fb   (Google Fast Pair — ignore)
   • fe2c1233..1237  [R/W/NOTIFY]
```

## Appendix B — Glossary

- **GATT** — Generic Attribute Profile (the service/characteristic model).
- **Characteristic** — a data value on the peripheral; has properties (read/write/notify).
- **CCCD (`0x2902`)** — descriptor the central writes to enable notify/indicate.
- **MTU** — max ATT payload per packet; default 23 (20 B usable).
- **RPA** — Resolvable Private Address; a rotating random address, resolvable only with the bonding IRK.
- **Notify vs Indicate** — Notify is unacknowledged (fast); Indicate is acknowledged (slower, reliable).
- **Central / Peripheral** — the app is the central; the controller is the peripheral (advertiser + GATT server).
