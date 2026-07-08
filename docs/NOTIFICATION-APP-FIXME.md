# EVdisplay Notifications — APP-SIDE FIX REQUIRED (board audited clean)

**Date:** 2026-07-08
**For:** the phone-app developer
**Frame byte layout / schema:** see `APP-NOTIFICATION-INTEGRATION.md` (same folder).

---

## Verdict (one paragraph)

The SG2002 dashboard firmware has been audited **end-to-end and PASSES at every
board stage.** Two independent proofs settle it: (1) the app's **trip-reset button
transmits a byte-perfect frame** to characteristic `0xAF07` (`DOWNLINK raw len=84
hex=AA50…4F444F5F52455345545F5452495000…E533`, decoded `category=32`, title
`ODO_RESET_TRIP`, executed by the board) — this exercises the *exact same* BLE
connection, GATT write path, frame encoder, and CRC16 that notifications use, so all
of those are proven working; and (2) a well-formed notification record injected
directly onto the board renders a pixel-visible banner (display agent screenshot of a
WhatsApp banner "CAN YOU SEE ME"; re-confirmed 2026-07-08 with an injected SMS record
producing UI line `[MID] notif seq=2001 cat=sms app="Messages" -> type=msg`), so the
decode → IPC → LVGL banner path is proven working. **The only broken stage is the
app's notification-event capture/send code:** with the app connected and real
WhatsApp/SMS/call events firing on the phone, the board receives **zero** bytes on
`0xAF07`. No board-side change can fix this; the fix is entirely phone-side.

---

## The decisive clue

**Manual button works. Event-driven capture does not.** Reset-trip (a user tap on
your foreground UI) → encoder → `writeCharacteristic(0xAF07)` = **works, byte-perfect.**
WhatsApp/SMS/Telegram/call (OS-delivered events) → **never produce a write.** That
rules out BLE connectivity, GATT handle/cache, MTU, encoder correctness, CRC, and the
write mechanism — all proven by the reset frame. The break is strictly **upstream of
the encoder**, in the notification-capture layer only.

---

## Ordered fix list (most likely first)

### 1. Confirm the `NotificationListenerService` actually fires
A user tap runs on your Activity thread; notification capture is a separate OS-bound
service that is easy to have silently dead.
- **Add a logcat line as the first statement in `onNotificationPosted(...)`.** Send
  yourself a WhatsApp message. **If that line does not print, the encoder is
  irrelevant — the listener is not delivering events.** This single check splits the
  bug in half (see "self-verify" below).
- Notification-access permission that was **just toggled usually needs an app restart
  or phone reboot** to actually bind the service.
- The service must be declared and bound **independently of your Activity.** If you
  only register/listen while the app is foregrounded, real notifications (app
  backgrounded) never reach you.
- **Battery optimization / Doze kills the listener.** Exempt the app.

### 2. Confirm the listener is wired to the SAME write path as the button
If step 1's log line *does* fire, the callback isn't reaching the write.
- Reset button → encoder → write = works. `onNotificationPosted` → **???** — verify it
  invokes the **same** `writeCharacteristic(0xAF07)` method the button uses, on the
  **same** connected GATT session. Common bug: the listener runs in the service
  process/thread with no reference to the live GATT client, so it builds a frame and
  drops it (or NPEs) instead of writing.
- Log immediately before the write call **and** log the write-status callback result.

### 3. Calls need a DIFFERENT API — likely unimplemented
Incoming/ongoing calls do **not** arrive through `NotificationListenerService`.
- Add `READ_PHONE_STATE` + a `TelephonyCallback` / `PhoneStateListener` (or a
  `CallScreeningService`), then emit a frame with `category=1`. If you only built the
  notification listener, calls will never generate a frame regardless of anything else.

### 4. Field values the firmware requires (only matters once frames transmit)
Mirror the reset frame's structure, but set:
- **Call:** `category=1`
- **SMS:** `category=2`
- **WhatsApp / Telegram:** `category=3`, `appName` containing `"whatsapp"` / `"telegram"`
  (matched case-insensitively; the board disambiguates messaging apps on `appName`).
- **`title` and `body` must be non-empty.** The UI filters empty-content frames, and
  also `category=0` and `removed=1`. Do not forward those.
- Byte-exact capnp layout + framing (`[0xAA][LEN][capnp][CRC16-CCITT-LE]`, CRC poly
  `0x1021`, init `0xFFFF`, MSB-first, no final XOR, over `LEN||payload`): see
  `APP-NOTIFICATION-INTEGRATION.md`.

---

## How to self-verify in 60 seconds (board + phone side by side)

**Board terminal** (keep open):
```
sshpass -p root ssh root@192.168.42.1 'tail -f /var/log/ble-gatt.log'
```
Trigger a real WhatsApp message (or SMS/call) on the phone, then read the board:
- **A `ble-gatt: DOWNLINK raw len=… hex=AA…` line appears** → the app transmitted;
  the rest of the chain is already proven — if no banner, paste that hex to the
  firmware team and it will be decoded byte-by-byte. **You are done on the send side.**
- **No line appears** → the app sent nothing. The bug is in steps 1–3 above. Now split
  it in half:

**Phone terminal:**
```
adb logcat | grep -i onNotificationPosted
```
Send the WhatsApp message again:
- **`onNotificationPosted` logs but no board `DOWNLINK raw`** → listener fires,
  write path not wired → **fix step 2** (and step 3 for calls).
- **`onNotificationPosted` does NOT log** → listener not firing → **fix step 1**
  (permission rebind / foreground-only registration / battery optimization).

---

## Stage ownership + status

| Stage | Owner | Status | Evidence |
|---|---|---|---|
| Notification/call event capture (Android) | **App** | **FAIL** | 0 bytes on `0xAF07` for real events; `onNotificationPosted` unverified |
| Frame encode (capnp + `[0xAA][LEN][…][CRC16]`) | App | PASS | reset frame byte-perfect (`…E533`) |
| BLE write to char `0xAF07` | App / (transport = comm) | PASS | reset frame delivered on live connection |
| BLE RX on `0xAF07` (Write + WriteWithoutResponse) | Board | PASS | char props `0x0C`; both `ATT_WRITE_REQ`/`ATT_WRITE_CMD` dispatch to decoder |
| Frame decode + CRC + capnp | Board | PASS | reset frame decoded byte-perfect |
| Command vs notification routing | Board | PASS | `category=32`→command; `category 1/2/3`→banner IPC |
| IPC `/var/run/phone_notification` (atomic, 13 keys, seq++) | Board | PASS | injected `seq=2001` written cleanly |
| LVGL banner (classify + render, MAIN screen) | Board (display) | PASS | injected SMS → `[MID] notif … -> type=msg`; WhatsApp screenshot |

**Note:** banners render on the **MAIN** cluster screen only (not the maps screen) and
auto-expire after ~12 s (except `ongoing` frames).

**Bottom line:** every board stage PASSES. Ship the phone-side capture fix, then watch
for the `DOWNLINK raw` line — that single log line is your success signal.
