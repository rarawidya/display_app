# EVdisplay — Notifications Not Showing: Board-Side Response & Audit

**Date:** 2026-07-20
**Author:** firmware / display team (SG2002 board, `ble-gatt-server.c` + LVGL banner UI)
**In reply to:** `Fromappdeveloper/NOTIFICATION-DISPLAY-DEBUG.md` (2026-07-20, phone-app side)
**Board under test:** live unit, `ble-gatt-server` md5 `0c5a5c3d…`, `/var/log/ble-gatt.log`

---

## 0. Verdict (one paragraph)

We ran your split-the-chain procedure against the live board today. **Every board-side
stage in your §5 checklist PASSES, and both board suspects you flagged in §2/§5.4 are
already correct.** During a real WhatsApp **message + call** test, the board received
**zero** frames on `0xAF07` — no `DOWNLINK raw`, no `DOWNLINK notif`, no CRC/reject
drops, and `/var/run/phone_notification` was never created — **while the same connected
phone was successfully writing NAV route + TIME_SYNC frames through the exact same GATT
write path.** By your own §1 decision table this is the top row ("nothing at all" on the
board) → **§4 App-A** (listener not bound / relay toggle off / Doze / `READ_PHONE_STATE`).
No board code was changed; none is required. We need your `NotifRelay` logcat to close it.

---

## 1. The one measurement (your §1) — result

Board terminal, phone connected, triggered one WhatsApp message **and** one call:

```
ble-gatt: LE central connected 72:B1:50:E6:C4:44
ble-gatt: NAV RouteSummary route=2 dist=5632m dur=698s dest="Stasiun Mojokerto"   <-- phone IS writing
ble-gatt: TIME_SYNC clock set epoch=1784538515 tz_offset_min=420 (local, rtc-saved) <-- phone IS writing
# ... and nothing else. Counts this connection:
DOWNLINK notif frames : 0
NON-TIME_SYNC raw     : 0
CRC/reject/bad-frame  : 0
/var/run/phone_notification : absent
```

Mapped onto your §1 table: **"Phone shows … / Board `DOWNLINK raw` = none"** → row 1
("nothing at all") or, if your `NotifRelay` shows `writing 0xAF07 … delivered` with **no**
board `DOWNLINK raw`, row 3 (transport). We can't see the phone log — **please attach it**
so we know which. Given NAV + TIME_SYNC arrive fine on the same socket, a transport-layer
loss that spares NAV/TIME_SYNC but eats notifications is implausible; App-A is far likelier.

---

## 2. Board audit — your §5 checklist, line by line

| Your item | Board reality | File:line | Result |
|---|---|---|---|
| §5.1 accept **Write + Write-Without-Response** on `0xAF07` | RX char props = `0x0C` (Write \| WriteNoRsp); ATT_WRITE_REQ accepted | `ble-gatt-server.c:1090` | ✅ PASS |
| §5.2 CRC16-CCITT over **LEN‖payload**, poly `0x1021`, init `0xFFFF`, MSB-first, **no** final XOR, RX little-endian | `crc16_ccitt(&val[1], plen+1)` — covers LEN byte + payload; identical constants | `:199`, applied `:731` | ✅ PASS (byte-identical to your spec) |
| §5.3 capnp decode `id/timestampUnix/category/flags/appName/title/body` | `read_PhoneNotification()`; data offsets 0/4/8/9 + ptr 0/1/2; schema id `0xb39c7a21e4d05f68` | `capnp/phone_notification.capnp.c` | ✅ PASS |
| **§5.4 / §2 case-insensitive `appName` match** (your live suspect) | `notif_ci_contains()` lowercases both sides via `(c \| 0x20)` **before** compare; `"WhatsApp"`/`"Telegram"` match | `custom.c:749`, used `:1310-1311` | ✅ **already case-insensitive** |
| §5.5 atomic IPC write, seq++ | `write_notification_ipc()` writes `….tmp` then `rename()` | `ble-gatt-server.c:327` | ✅ PASS |
| §5.6 LVGL banner on MAIN cluster, ~12 s expiry, `ongoing` persists | proven by injected record (banner rendered) this session | `custom.c` notif path | ✅ PASS |

### Two clarifications that rule out §5.4 as the current cause

1. **`appName` never gates whether a banner shows.** Routing is purely by `category`:
   `category == 32` → control command; **everything else (1/2/3) → `write_notification_ipc`
   → banner**. `appName` is consumed *only* to pick the icon/accent
   (`notif_ci_contains(app,"whatsapp"/"telegram")`), with a WhatsApp-style default
   fallback. So an unrecognized/miscased `appName` can at worst yield the **wrong icon**,
   never a missing banner. Your §5.4 concern is valid for "shows but looks wrong"; it
   cannot produce today's "nothing shows."
2. We confirmed the compare **is** case-insensitive anyway, so even the icon is correct
   for your capitalized `"WhatsApp"`/`"Telegram"`.

---

## 3. What we still need from you (your §7)

To convert row-1-or-3 into a definite answer, please attach:

1. `adb logcat -s NotifRelay` across **one WhatsApp message + one call**, specifically:
   - Does `onListenerConnected — system bound the notification listener` print? (your §3.4)
   - For the message: `onNotificationPosted pkg=com.whatsapp …` → `writing 0xAF07 … cat=3`
     → `push … → delivered|dropped`?
   - For the call: is `CallStateRelay` registered (`call-state relay registered`)?
2. Confirm the **§3 preconditions on THIS phone** — most likely culprits, in order:
   - **§3.2 "Mirror to vehicle display" toggle — default OFF.** The service drops every
     event silently when off, and `CallStateRelay` only registers while it's on. This one
     toggle explains *both* message and call silence at once.
   - §3.1 Notification access granted (revoked on app update).
   - §3.4 listener actually **bound** (needs app restart after granting).
   - §3.5 battery/Doze exemption.
   - §3.3 `READ_PHONE_STATE` (calls only).
3. The exact `pkg=` string from `onNotificationPosted` (in case it's an OEM/clone package
   outside your allow-list).

**Useful signal we already have:** earlier this session a **native-dialer** call *did*
mirror end-to-end (your `CallStateRelay` telephony path works — we saw
`DOWNLINK notif cat=1(call) app="Phone" body="Incoming call"` and drove Answer/Reject on
`0xAF05`). Today's failure covers WhatsApp **message** *and* WhatsApp **call** — both ride
your `NotificationListenerService`, not telephony. A working telephony path + a silent
listener path points squarely at §3.2/§3.4 (relay toggle / listener binding), not the wire.

---

## 4. One reconcile item (not the current bug)

Frame-size ceiling. Your §2 caps the whole frame at **≤ 244 B (payload ≤ 240 B)** — good,
that fits one 247-MTU `ATT_WRITE_REQ` (max 244 payload). Our schema text caps
(`appName ≤ 24`, `title ≤ 48`, `body ≤ 96`) permit a worst-case encoded payload of ~248 B
(frame ~252 B), which would **exceed** a single write and be truncated (there is no
reassembly on the board). As long as you keep your 240-byte cap we never collide. If you'd
rather we enforce it board-side too, we'll tighten the caps to match — say the word. No
effect on the present zero-frame symptom.

---

## 5. Bottom line

- Board wire contract, CRC, decode, routing, IPC, and banner: **all verified PASS today.**
- Case-insensitive `appName`: **confirmed present**; it can only affect icon choice, not visibility.
- Live proof the fault is upstream: **NAV + TIME_SYNC frames arrive, notif frames = 0**, from the same connected phone on the same `0xAF07` write path.
- Action is app-side (§3 preconditions). Send the `NotifRelay` log and we'll pinpoint the exact precondition together.

### Board source paths (for your byte-verification)
- `EVDISPLAY/ble-gatt/ble-gatt-server.c` — `crc16_ccitt` `:199`, downlink parse/CRC/decode/route `:718-752`, IPC writer `:327`, RX char props `:1090`
- `EVDISPLAY/ble-gatt/capnp/phone_notification.capnp.{c,h}` — codec (schema `0xb39c7a21e4d05f68`)
- `display_ui/DisplayUI_thema3/custom/custom.c` — `notif_ci_contains` `:749`, app→icon map `:1310`
- Authoritative wire spec (unchanged): `EVDISPLAY/bt-telemetry/capnpble.md` / `APP-NOTIFICATION-INTEGRATION.md`
