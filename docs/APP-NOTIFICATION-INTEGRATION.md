# EVdisplay — Phone→Board Notification Integration Spec (v1)

**Audience:** phone-app developer implementing the notification push.
**Board side status:** the BLE GATT server (`ble-gatt-server.c`) and the on-screen
banner UI are **implemented and verified end-to-end on hardware (2026-07-07)**.
This spec is derived from the actual server decode path and the UI classifier —
NOT from the older `DOWNLINK.md` / `BLE-TRANSPORT.md` drafts (those are transport-superseded).

The app encodes and pushes a **Cap'n Proto `PhoneNotification`**, wrapped in a small
frame, in **one BLE write**.

---

## 1. BLE transport — how to reach the board and push one notification

| Item | Value |
|---|---|
| Role | Board is a **BLE GATT peripheral**; your app is the central/client |
| Device name to scan for | **`EVdisplay`** (exact case) |
| Advertised service UUID | **`0xAF00`** (16-bit) |
| Notification RX characteristic (you WRITE this) | **`0xAF07`** (16-bit), value handle `0x0009` |
| RX properties | **`0x0C` = Write (0x08) \| Write-Without-Response (0x04)** — either works |
| Telemetry TX characteristic (board→you) | `0xAF08`, Notify — VOTOL telemetry uplink, **not used for notifications** |
| Pairing/bonding | **None. Open, unencrypted GATT.** Do not bond. |
| MTU | Board `MY_MTU = 247` and initiates Exchange-MTU on connect. Negotiated = min(yours, 247). |

**Delivery model — read carefully:**
- **One notification = exactly one GATT write of one complete frame.** The server does
  **not** reassemble across writes. A split or short write is dropped.
- The **entire frame must fit in a single ATT write value = (negotiated MTU − 3) bytes**.
  At max MTU 247 that is **≤ 244 bytes/frame**. Keep `payload ≤ 240 bytes`.
- **Negotiate MTU first** (or accept the board's request), then write the whole frame in one op.
  No CCCD subscription needed to push notifications.

**App flow:**
1. Scan → connect to `EVdisplay`.
2. Discover service `0xAF00`, characteristic `0xAF07`.
3. Ensure MTU ≥ (largest frame + 3); the board asks for 247, so accept it.
4. For each alert: build the frame (§2) and issue **one** write to `0xAF07`.

---

## 2. Payload — the framed Cap'n Proto message

### 2a. Frame wrapper (what goes in the write value)

```
[0xAA] [LEN] [ capnp payload : LEN bytes ] [ CRC16 : 2 bytes little-endian ]
  1  +  1  +          LEN               +            2
```

- `0xAA` — sync byte.
- `LEN` — one byte, payload length `0..255`.
- `payload` — the **unpacked, single-segment** Cap'n Proto `PhoneNotification` (do NOT use the packed writer).
- `CRC16` — **CRC16-CCITT**: poly `0x1021`, init `0xFFFF`, no final XOR, MSB-first, computed over
  **`LEN` byte ‖ payload**. Sent **low byte first**. Board recomputes over `LEN‖payload`, drops on mismatch.

### 2b. Cap'n Proto schema (schema id `0xb39c7a21e4d05f68`)

```capnp
struct PhoneNotification {
  id            @0 :UInt32;   # correlate updates/dismissals for one alert
  timestampUnix @1 :UInt32;   # unix seconds posted; 0 = unknown
  category      @2 :UInt8;    # see §3
  flags         @3 :UInt8;    # bit0 ongoing, bit1 removed, bit2 silent
  appName       @4 :Text;     # <= 24 UTF-8 bytes
  title         @5 :Text;     # <= 48 UTF-8 bytes
  body          @6 :Text;     # <= 96 UTF-8 bytes
}
```

**These 7 fields are the ONLY things your app controls.** Ordinals `@0..@6` are frozen.

| capnp field | Type | Required? | Valid values / caps | Meaning |
|---|---|---|---|---|
| `id` | UInt32 | Recommended | any | Stable per-alert id. Same `id` for updates/dismissal of one alert. |
| `timestampUnix` | UInt32 | Optional | unix secs, `0`=unknown | Parsed but UI does not display/expire on it (informational). |
| `category` | UInt8 | **Required** | `0..6` (≥7 → "other") | Drives icon/behavior — see §3. |
| `flags` | UInt8 | Optional (default 0) | bitfield | bit0 `ongoing`, bit1 `removed`, bit2 `silent`. bits 3-7 = 0. |
| `appName` | Text | **Required for category 3**, else optional | ≤ 24 B, UTF-8 | Disambiguates WhatsApp vs Telegram (§3). Truncate on codepoint boundary. |
| `title` | Text | Recommended | ≤ 48 B | Sender / contact → banner line 1. |
| `body` | Text | Recommended | ≤ 96 B | Message preview / call state → banner line 2. |

Text fields: empty/absent = send an empty string. Board also sanitizes on receipt
(control chars → space; re-truncates to 24/48/96), but pre-truncate so the frame fits the MTU.

### 2c. What the board fills in — do NOT send these

The `/var/run/phone_notification` file the UI reads has extra keys derived **server-side**:
- `seq` — monotonic counter, +1 per accepted frame. You cannot set it (UI freshness signal, §4).
- `category_name` — string form of `category`.
- `recv_ms` — board monotonic receive time (parsed, not currently used).
- `ongoing`/`removed`/`silent` — the three low bits of your `flags` byte, split out.

So: **you send `flags` as a byte; you never send `seq`, `category_name`, or `recv_ms`.**

---

## 3. `category` → on-screen mapping, and the app-string rule

Classifier logic: **category first, then `appName` substring for messaging apps.**

| `category` | On-screen result | Notes |
|---|---|---|
| `1` | **Call** | Call glyph. Set `flags.ongoing` for in-progress/incoming call. |
| `2` | **Message (SMS)** | SMS glyph. |
| `3` + `appName` contains `whatsapp` | **WhatsApp** | brand icon |
| `3` + `appName` contains `telegram` | **Telegram** | brand icon |
| `3` + any other appName | **Generic** (message glyph) | still shown |
| `6` | **Clear all** | Collapses the banner. `title`/`body` may be empty. |
| `0` other, `4` email, `5` calendar, `≥7` | **Generic** (message glyph banner) | shown, not ignored |

**App-string matching rule:** **case-insensitive substring** on `appName`.
- `whatsapp` substring → WhatsApp (`"WhatsApp"`, `"WhatsApp Business"`, `"whatsapp"` all match).
- `telegram` substring → Telegram (`"Telegram"`, `"Telegram X"`, `"telegram"` all match).

**Canonical strings to send:**

| App | `category` | `appName` |
|---|---|---|
| WhatsApp | `3` | `WhatsApp` |
| Telegram | `3` | `Telegram` |
| SMS / text | `2` | `Messages` (appName not used for classification) |
| Phone call | `1` | `Phone` (appName not used for classification) |

To add another branded app later we add one substring on our side — coordinate with us.

---

## 4. Freshness / update / dismiss / ongoing semantics

- **Latest-wins, single banner.** Board keeps only the newest notification; UI shows **one**
  banner at a time (no stacking). Every accepted frame bumps `seq`; UI re-renders on increase.
  You get correct behavior automatically by sending one frame per event.
- **Update/replace:** new frame with **same `id`** + updated `title`/`body`.
- **Dismiss:** frame with `flags.removed = 1` (bit1) → collapses the current banner.
- **Clear everything:** `category = 6` → collapses.
- **Ongoing vs transient:**
  - `flags.ongoing = 1` (bit0) → **persistent** (use for active/incoming call). Send an explicit
    `removed`/`clear` frame when it ends.
  - `flags.ongoing = 0` → **transient**, UI auto-collapses after **~12 s**.
- `flags.silent` (bit2) is parsed but not yet visually differentiated.

---

## 5. Concrete examples

### 5a. Message / SMS — verified golden frame (byte-exact target)

Fields: `id=4242, timestampUnix=1751800000, category=2, flags=0, appName="Messages", title="Alice", body="On my way"`.
Your encoder MUST produce these exact bytes (verified on the board's own decoder):

```
AA 60 00 00 00 00 0B 00 00 00 00 00 00 00 02 00 03 00 92 10 00 00 C0 58 6A 68
02 00 00 00 00 00 00 00 09 00 00 00 4A 00 00 00 0D 00 00 00 32 00 00 00 0D 00
00 00 52 00 00 00 4D 65 73 73 61 67 65 73 00 00 00 00 00 00 00 00 41 6C 69 63
65 00 00 00 4F 6E 20 6D 79 20 77 61 79 00 00 00 00 00 00 00 E1 1C
```
Frame = 100 bytes, `LEN = 0x60 = 96`, CRC16 over `LEN‖payload` = `0x1CE1` → wire `E1 1C`.
**Use this as your encoder self-test.**

### 5b. WhatsApp message (field spec)

```
id=90001  timestampUnix=<now or 0>  category=3  flags=0
appName="WhatsApp"  title="Alice"  body="On my way!"
```
→ Renders a WhatsApp banner `Alice / On my way!` (transient, auto-collapse ~12 s).

### 5c. Incoming call, ongoing (field spec)

```
id=90002  category=1  flags=0x01   # ongoing -> persistent
appName="Phone"  title="Dad"  body="Incoming call"
```
When the call ends, dismiss:
```
id=90002  category=1  flags=0x02   # removed -> collapses
```

### 5d. Clear all

```
id=0  category=6  flags=0  appName=""  title=""  body=""
```

> Classification/show/update/dismiss/clear behavior was verified live. Only §5a is a
> byte-exact target; for 5b–5d, verify your encoder against 5a then change field values.

---

## 6. Connection / pairing notes & current limitations

**Pairing/connection:**
- GATT service is **open and unencrypted — no BLE bonding required** to write `0xAF07`.
- The board's **Classic-BT** SSP Just-Works pairing (kernel/AIC8800 force SSP; legacy PIN 1234
  path also exists) is the **Classic path and does NOT apply to this BLE push.** Ignore it.
- Board runs Exchange-MTU on connect; cooperate to get ≥ your frame size.

**Current limitations:**
1. **One write = one whole frame; no reassembly.** Frame ≤ (MTU−3) ≤ **244 bytes** (payload ≤ 240 B).
2. **Single-banner, latest-wins.** No stacking; `removed=1` clears the currently-shown banner
   rather than matching a specific `id`.
3. **`silent` parsed but not visually differentiated** (no chime exists). `timestampUnix`/`recv_ms`
   parsed but UI auto-expire uses its own clock.
4. Transport supports **both Android and iOS** BLE centrals (open GATT). Capture side is the app's
   responsibility (Android `NotificationListenerService`; iOS limited to CoreBluetooth + app access).

---

## Gap disclosure (our side)

- **Working now:** `0xAF07` write handler validates `[0xAA][LEN][payload][CRC16-LE]`, checks
  sync + length + CRC-CCITT over `LEN‖payload`, decodes the capnp `PhoneNotification`, atomically
  publishes to `/var/run/phone_notification`; LVGL UI polls and renders. MTU exchange implemented (247).
  The §1–§5 on-wire contract is real, not aspirational.
- **The one on-our-side constraint:** the server has **no RX reassembly** — each ATT write is treated
  as one complete frame. **If notifications must exceed ~244 bytes/frame, we must first add a
  reassembly buffer to `handle_downlink_write()`.** Within the 24/48/96 caps this is not needed.

### Source paths (ours, for reference)
- BLE server decode + IPC writer: `ble-gatt/ble-gatt-server.c` (`handle_downlink_write`,
  `write_notification_ipc`, UUID/name constants).
- Schema: `bt-telemetry/phone_notification.capnp`.
- UI classifier + banner: `display_ui/mid_800x480/custom/custom.c` (`notif_read`, `notif_classify`,
  `notif_apply_widgets`, `notif_poll_cb`).
