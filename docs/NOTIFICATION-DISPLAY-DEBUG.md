# EVdisplay — Notifications & Calls Not Showing on Display: Joint Debug / Audit

**Date:** 2026-07-20
**Author:** phone-app side (InnoRide / `com.innodrive.evdash`)
**For:** firmware / display team (SG2002 board, `ble-gatt-server.c` + LVGL banner UI)
**Symptom under test:** with the phone connected to `EVdisplay`, incoming **WhatsApp,
Telegram, SMS messages and phone calls do not render a banner on the display.**

> **Read this first — what changed since the last note.** The earlier
> [`NOTIFICATION-APP-FIXME.md`](NOTIFICATION-APP-FIXME.md) (2026-07-08) concluded the
> app had **no capture code** and calls used no telephony API. **That is now fixed.**
> The app today implements a live `NotificationListenerService`
> (`NotificationRelayService`) **and** a `TelephonyCallback`-based call relay
> (`CallStateRelay`). So the failure this doc chases is a *different* one than the
> 2026-07-08 note — the capture layer exists; we now need to find where the frame is
> lost between "event fires on phone" and "banner on screen." This doc is the
> split-the-chain procedure to localize it to **app** or **board**, plus the exact
> on-wire contract so the board team can byte-verify anything the app transmits.

Frame byte layout / capnp schema is unchanged — the authoritative spec is
[`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md). This doc adds
the current app behavior, the exact log signals, and the decision tree.

---

## 1. TL;DR — the one measurement that splits the bug in half

Run the phone and the board side-by-side and trigger **one real WhatsApp message**.

**Board terminal** (leave running):
```
sshpass -p root ssh root@192.168.42.1 'tail -f /var/log/ble-gatt.log'
```
**Phone terminal** (leave running):
```
adb logcat -s NotifRelay
```

Then read the two outputs against this table:

| Phone `NotifRelay` shows… | Board `DOWNLINK raw` line… | Conclusion | Go to |
|---|---|---|---|
| nothing at all | — | Listener never fired (not bound / relay off / Doze) | **§4 App-A** |
| `onNotificationPosted pkg=com.whatsapp …` but **no** `writing 0xAF07 …` | — | Captured but filtered/not sent (classifier dropped it, or relay toggle off) | **§4 App-B** |
| `writing 0xAF07 frame len=… cat=3` **and** `push … → delivered` | **no** `DOWNLINK raw` | App wrote to GATT, board saw nothing → **transport/GATT** | **§4 App-C / §5** |
| `writing 0xAF07 …` then `push … → dropped` | — | GATT write itself failed (not connected / char missing / write rejected) | **§4 App-C** |
| `writing 0xAF07 … → delivered` | `DOWNLINK raw len=… hex=AA…` **appears** | **App is done — bug is board-side** | **§5 Board** |

The single success signal for the app side is: **`writing 0xAF07 … → delivered` on the
phone AND a matching `DOWNLINK raw hex=AA…` on the board.** If both appear and there's
still no banner, the loss is entirely inside the board (decode / IPC / LVGL) — see §5.

---

## 2. What the app sends today (current behavior — verify against this)

The app relays exactly these event classes; **everything else stays on the phone.**
Mapping is in `NotificationClassifier.kt` and `CallStateRelay.kt`.

| Source | Trigger path | `category` | `appName` | `title` / `body` | `flags` |
|---|---|---|---|---|---|
| Phone call — ringing | `CallStateRelay` (TelephonyCallback) | **1** (CALL) | `"Phone"` | `"Phone"` / `"Incoming call"` | `0x01` ONGOING |
| Phone call — in call | `CallStateRelay` | **1** | `"Phone"` | `"Phone"` / `"In call"` | `0x01` ONGOING |
| Phone call — ended | `CallStateRelay` | **1** | `"Phone"` | `"Phone"` / `"Call ended"` | `0x00` |
| SMS | NotificationListener | **2** (SMS) | `"Messages"` | sender / text | `0x00` |
| WhatsApp | NotificationListener | **3** (MSG APP) | `"WhatsApp"` | sender / text | `0x00` |
| Telegram | NotificationListener | **3** (MSG APP) | `"Telegram"` | sender / text | `0x00` |

Notes the board team must reconcile:

- **Calls come from TWO independent code paths.** `CallStateRelay` (telephony API,
  the reliable path) uses a single fixed frame id `0x0CA11` so ring→talk→end replace
  the same banner. The `NotificationListenerService` *also* emits a call frame for
  call-style notifications (WhatsApp/Telegram VoIP, dialer full-screen intents). Both
  are `category=1`. The board may receive the same call as two banners with different
  ids — that is expected; latest-wins by id.
- **Messaging-app disambiguation is by `appName`.** WhatsApp and Telegram are **both
  `category=3`**; the only distinguisher is `appName` = `"WhatsApp"` / `"Telegram"`
  (capitalized). The 2026-07-08 board note said the board matches `"whatsapp"` /
  `"telegram"` **case-insensitively** — please **confirm the board still lowercases
  before comparing**, or the icon/label selection for these two will silently fall
  through to a default. This is a live suspect for "message shows but looks wrong /
  doesn't show for one app."
- **`title` and `body` are always non-empty** (classifier fills fallbacks). The app
  never forwards `category=0`, blank-content, group-summary, or `removed` frames — so
  the board's empty-content filter should never be exercised by us.
- A dismissed notification does **not** send a `removed` frame. Only an **ongoing
  call** that clears sends a transient `category=1 "Call ended"` frame. Regular
  message banners are left to the board's ~12 s auto-expire.

### On-wire frame (unchanged — full detail in `APP-NOTIFICATION-INTEGRATION.md`)

```
[0xAA] [LEN:u8] [ capnp PhoneNotification payload : LEN bytes ] [ CRC16-CCITT-LE : 2 ]
```
- One event = **one** GATT write to characteristic **`0xAF07`** on service `0xAF00`.
  No reassembly on the board; whole frame ≤ 244 B (payload ≤ 240 B).
- capnp `PhoneNotification`: `id:u32 LE | timestampUnix:u32 LE | category:u8 | flags:u8
  | 6×reserved(0) | 3 Text pointers (appName, title, body) | text region`.
- CRC16-CCITT: poly `0x1021`, init `0xFFFF`, MSB-first, **no** final XOR, computed over
  `LEN‖payload`, transmitted little-endian.
- Write is **acknowledged** (`WRITE_TYPE_DEFAULT` / ATT_WRITE_REQ). Board char props
  are `0x0C` (Write | Write-Without-Response), so this is accepted.

**The trip-reset button uses this exact same encoder, CRC, GATT write, and connection
(`category=32`, title `ODO_RESET_TRIP`).** If reset-trip works but notifications don't,
the transport/encoder/CRC are proven and the loss is upstream (capture/classify) or
board-side (decode/IPC/UI) — never in the framing itself.

---

## 3. Preconditions the app requires (all must hold, or nothing transmits)

If **any** of these is false, the app sends **zero** bytes for real events — and this
is the most common cause of "it just doesn't work." Verify all before blaming the wire:

1. **Notification access granted** to the app (system setting, *not* a normal runtime
   permission): Settings → Apps → Special access → Notification access →
   *Vehicle display notification relay* = ON. App exposes it at **Settings → Phone
   notifications → Grant notification access**.
2. **In-app toggle ON:** Settings → Phone notifications → **"Mirror to vehicle
   display"**. This is `AppSettings.notificationRelayEnabled` (**default OFF**). The
   service checks it on **every** event and drops silently when off
   (`relay disabled — dropping …` at log level `v`). **Calls also require this toggle**
   — `CallStateRelay` only registers its telephony listener while the toggle is on.
3. **`READ_PHONE_STATE` granted** — required for call mirroring. Without it, messages
   still work but calls never generate a frame (log: `relay enabled but
   READ_PHONE_STATE not granted — calls won't mirror`).
4. **Listener is actually bound.** After a fresh install/update or a just-granted
   access permission, Android frequently does **not** bind the service until an app
   restart or a rebind nudge. Confirm `onListenerConnected — system bound the
   notification listener` appears in logcat. If you see `onListenerDisconnected`, no
   messages will relay until it rebinds.
5. **Battery optimization exempted.** Doze can unbind the listener mid-ride. Settings →
   Phone notifications → **"Allow background delivery"**. Symptom: works when screen on
   / app foreground, dies after a while backgrounded.
6. **Connected to the board.** The GATT write no-ops (`push … → dropped`) if the BLE
   session isn't live. The reset-trip button is a good "am I really connected?" probe.

---

## 4. App-side diagnosis (by branch from §1)

All app logs use tag **`NotifRelay`** → filter with `adb logcat -s NotifRelay`.
The three key lines:
- `onNotificationPosted pkg=… key=…` — the listener received an OS event.
- `writing 0xAF07 frame len=… id=… cat=…` — a frame was built and is about to be written.
- `push id=… cat=… flags=… → delivered|dropped` — the GATT write result.

### App-A — no `onNotificationPosted` at all
The listener isn't delivering events. Almost always a **binding / permission / Doze**
issue, not code. Work §3 items 1, 4, 5 in order. Force it: toggle notification access
OFF→ON, then fully restart the app (or reboot). Confirm `onListenerConnected` prints.
For **calls specifically**, this branch instead means `CallStateRelay` isn't
registered — check §3 items 2 and 3 and look for `call-state relay registered`.

### App-B — `onNotificationPosted` fires but no `writing 0xAF07`
The event was captured but **dropped before send**. Causes, in order:
- Relay toggle OFF → look for `relay disabled — dropping <pkg>` (verbose). Fix: §3.2.
- The classifier returned null → the package isn't in the allow-list, or it was a
  group-summary / blank-content / ongoing (media) notification. Only the packages in
  the table in §2 are relayed. If the user's WhatsApp/SMS package name differs (OEM
  fork, dual-app clone, business app), it's filtered. **Report the exact `pkg=` string
  from the log** so we can extend the allow-list.
- Consecutive byte-identical frame de-dupe (rare for real messages).

### App-C — `writing 0xAF07 …` prints, but result is `dropped` (or `delivered` with no board RX)
- `→ dropped`: the GATT write failed — not connected, characteristic `0xAF07` not
  discovered, or the stack rejected/timed out (3 s). Re-verify the board connection
  with the reset-trip button. If reset also fails, it's a connection problem, not a
  notification problem.
- `→ delivered` **but board shows no `DOWNLINK raw`**: the app handed a complete frame
  to the Android BLE stack and got an ACK, yet the board's server logged nothing. This
  is a genuine **transport/GATT-layer** split — capture the `len=` and the board-side
  radio trace and hand both to §5. (Delivered = the local stack accepted the write; it
  is not proof the peer's application layer processed it, but with an acknowledged
  write a missing board-side log strongly implicates board RX handling / handle
  mismatch / notify-vs-write confusion.)

---

## 5. Board-side audit checklist (once a `DOWNLINK raw` line appears)

If the phone logs `writing 0xAF07 … → delivered` **and** the board logs `DOWNLINK raw
len=… hex=AA…`, the app has done its job. Please verify each board stage and report
where it stops:

1. **Frame accepted on `0xAF07`** for *both* Write and Write-Without-Response? (App
   uses acknowledged Write / ATT_WRITE_REQ.) Confirm the value handle matches the app's
   discovered handle — a stale GATT cache on the phone or a changed handle on the board
   can misroute the write.
2. **CRC16 passes** on the received `LEN‖payload` (poly `0x1021`, init `0xFFFF`,
   MSB-first, no final XOR). Paste the raw hex if it fails — we'll diff byte-for-byte.
3. **capnp decode** yields the fields in §2 (id, timestampUnix, category, flags,
   appName, title, body). Log the decoded `category` and `appName`.
4. **Routing:** `category 1/2/3` → banner IPC (not command). Confirm `category=3` with
   `appName="WhatsApp"`/`"Telegram"` is **not** dropped by a case-sensitive compare
   (see §2 note — lowercase before matching).
5. **IPC** `/var/run/phone_notification` written (seq++), atomically.
6. **LVGL banner** classifies + renders on the **MAIN** cluster screen (banners do
   **not** show on the maps/navigation screen), auto-expires ~12 s except `ongoing`
   (call) frames.

The 2026-07-08 audit found **every** board stage PASSING against injected records. If
that still holds and real app frames now arrive on the wire, the banner should render;
if it doesn't, the regression is between "frame on `0xAF07`" and "IPC write" — that's
the segment to re-audit with the actual hex the app sent.

---

## 6. Quick reference — commands

```bash
# Phone: watch the whole relay chain (capture → build → write result)
adb logcat -s NotifRelay

# Phone: is the listener even bound?
adb logcat -s NotifRelay | grep -i "onListenerConnected\|onListenerDisconnected"

# Board: did a frame arrive on 0xAF07?
sshpass -p root ssh root@192.168.42.1 'tail -f /var/log/ble-gatt.log'   # look for: DOWNLINK raw len=… hex=AA…

# Sanity: prove the transport end-to-end without notifications
#   → tap Home → Trip A → Reset in the app; board must log a category=32 ODO_RESET_TRIP frame.
#   If reset works but messages don't, transport/encoder/CRC are NOT the problem.
```

## 7. What to send back to the phone-app team

When escalating, please attach:
- The phone `NotifRelay` log for one triggered message **and** one triggered call.
- The board `DOWNLINK raw hex=…` line (or a clear statement that none appeared).
- For a filtered message: the exact `pkg=` string from `onNotificationPosted`.
- For a wrong/absent messaging-app icon: the decoded `appName` string as the board saw it.

---

### Related docs
- [`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md) — authoritative frame/capnp byte spec.
- [`NOTIFICATION-APP-FIXME.md`](NOTIFICATION-APP-FIXME.md) — prior (2026-07-08) app-side capture fix; now implemented.
- [`CALL-CONTROL-INTEGRATION.md`](CALL-CONTROL-INTEGRATION.md) / [`CALL-ACTION-INTEGRATION.md`](CALL-ACTION-INTEGRATION.md) — board→phone answer/hang-up control on `0xAF05`.
