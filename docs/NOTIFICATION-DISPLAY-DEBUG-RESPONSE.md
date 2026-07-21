# EVdisplay — Notifications Not Showing: Board-Side Response & Audit

**Date:** 2026-07-20
**Author:** firmware / display team (SG2002 board, `ble-gatt-server.c` + LVGL banner UI)
**In reply to:** `Fromappdeveloper/NOTIFICATION-DISPLAY-DEBUG.md` (2026-07-20, phone-app side)
**Board under test:** live unit, `ble-gatt-server` md5 `0c5a5c3d…`, `/var/log/ble-gatt.log`

---

## ⚠️ UPDATE (later same day, 2026-07-20) — frames now flow; two follow-on issues

After the app-side preconditions were addressed, **notifications now reach the board and
render** — the original "zero frames" state below (§0–§3) is resolved. Two follow-on
issues remain, both app/platform-side, detailed in the new **§5** and **§6**:

1. **Only WhatsApp *Business* shows, not regular WhatsApp** — different Android packages;
   the app allow-list is missing `com.whatsapp`. See **§5**.
2. **A WhatsApp call can't be *answered* from the dashboard** — VoIP calls are display-only
   on Android; only native cellular calls are answerable. See **§6**.

§0–§4 below are retained as the original audit record (still valid: the board wire/decode/
routing/banner path is proven correct).

---

## Coverage matrix — every notification type & call action

### A. Messages / alerts  (phone → board banner)

| Source | Android package (`pkg=`) | `category` | `appName` | Board renders banner? | Status |
|---|---|---|---|---|---|
| WhatsApp (consumer) | `com.whatsapp` | 3 | `"WhatsApp"` | ✅ | **app must add to allow-list** (§5) |
| WhatsApp **Business** | `com.whatsapp.w4b` | 3 | `"WhatsApp"` | ✅ | working now |
| Telegram | `org.telegram.messenger` | 3 | `"Telegram"` | ✅ | supported |
| SMS / Messages | OEM pkg (`com.google.android.apps.messaging`, `com.samsung.android.messaging`, …) | 2 | `"Messages"` | ✅ | supported |
| Any other app | (its pkg) | 0 or 3 | app's name | ✅ generic banner | shows if app allow-lists it |

Board note: banner **visibility** depends only on `category` (1/2/3 → banner). `appName`
just selects the icon (WA / TG / generic), matched case-insensitively. So adding a package
to your allow-list is sufficient for it to appear.

### B. Calls  (board ↔ phone, control on `0xAF05` CallControl)

CallControl `action` codes (`0xAF05`, per `CALL-CONTROL-INTEGRATION.md` §4): **`1`=answer,
`2`=hangUp, `3`=dismissBanner.** Note **Reject/Decline is NOT a separate code — it is
`2` hangUp** (`TelecomManager.endCall` ends a ringing *or* active call). `3` is
board-local only (clears the banner, no phone-side action).

| Action from dashboard | Frame | Native cellular call | WhatsApp / Business (VoIP) call |
|---|---|---|---|
| Show incoming-call banner | (inbound `0xAF07` `cat=1`) | ✅ | ✅ display only |
| **Answer / Accept** | `action=1` (`acceptRingingCall`, Android 8.0+, needs `ANSWER_PHONE_CALLS`) | ✅ verified | ❌ no 3rd-party VoIP answer API |
| **Reject / Decline** | `action=2` hangUp (`endCall`, Android 9+) | ✅ verified | ❌ dismiss only |
| **End / Hang-up** | `action=2` hangUp | ✅ verified | ❌ dismiss only |
| Dismiss banner (local) | `action=3` | ✅ (no phone action) | ✅ (no phone action) |
| Auto ring → in-call → ended updates | inbound `0xAF07` | ✅ same banner, latest-wins by id | banner only |

Board note: answer / hang-up(=reject) / dismiss are already implemented and **verified
end-to-end this session** for a native dialer call — the board emits the `0xAF05`
CallControl frame (`x3`, same seq); your `CallStateRelay` executes it via `TelecomManager`.
VoIP-call actions are a **platform limitation**, not a board or app bug (§6).

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

## 5. WhatsApp Business ≠ WhatsApp — different Android packages

Once frames started flowing, **only WhatsApp Business rendered; regular WhatsApp did not.**
The two apps are separate installs with **different package names**, so your
`NotificationClassifier` allow-list (which matches by `pkg=`) treats them as unrelated:

| App | Android package (`pkg=`) |
|---|---|
| WhatsApp (consumer) | `com.whatsapp` |
| **WhatsApp Business** | `com.whatsapp.w4b` |
| Telegram | `org.telegram.messenger` |
| SMS (device-dependent) | `com.google.android.apps.messaging` / `com.samsung.android.messaging` / OEM |

**Diagnosis:** your allow-list matches `com.whatsapp.w4b` but **not** `com.whatsapp`, so
every regular-WhatsApp notification is captured and then **dropped at your §4 App-B branch**
("classifier returned null — package not in allow-list"). This is exactly the case your
`NOTIFICATION-DISPLAY-DEBUG.md` §4 App-B flagged.

### Fix — app side
Add **both** packages, mapped to `category=3`, `appName="WhatsApp"` (send `"WhatsApp"` for
both — don't split Business into its own label; the board has one WhatsApp icon):

```kotlin
// NotificationClassifier — messaging allow-list
"com.whatsapp"           -> Msg(category = 3, appName = "WhatsApp")   // consumer
"com.whatsapp.w4b"       -> Msg(category = 3, appName = "WhatsApp")   // Business
"org.telegram.messenger" -> Msg(category = 3, appName = "Telegram")
// add OEM SMS packages as needed -> category = 2, appName = "Messages"
```

> Cloned / dual-app instances (OEM "dual app", parallel-space) run WhatsApp under a
> different user profile or wrapper package. If a user runs a clone, capture the live
> `pkg=` from `onNotificationPosted` and add it — the board doesn't care which package.

### Board side — already correct, no change
The board picks the WhatsApp icon with a **case-insensitive substring** match,
`notif_ci_contains(app, "whatsapp")`, so `"WhatsApp"`, `"WhatsApp Business"`, and
`"whatsapp"` all resolve to the same green WA icon; routing to a banner is by `category`
(1/2/3), **independent of `appName`**. The moment you relay `com.whatsapp`, the board shows
it. (`custom.c:749`, `:1310`.)

---

## 6. "Can't accept the call" — native vs VoIP (platform limitation)

Answering a call **from the dashboard** works for one call type only:

| Call type | Arrives on phone as | Board can display? | Board can **answer**? |
|---|---|---|---|
| Native cellular (dialer) | Telephony (`CallStateRelay`) | ✅ yes | ✅ **yes** — verified: board→app `CallControl action=answer` on `0xAF05`, app answers via `TelephonyManager`/`InCallService` |
| **WhatsApp / WhatsApp Business call (VoIP)** | Notification (NotificationListener) | ✅ yes (banner) | ❌ **no** |

**Why WhatsApp calls can't be answered programmatically:** a WhatsApp voice/video call is
delivered as a (full-screen) notification, and **Android exposes no public API for a
third-party app to accept another app's VoIP call.** The relay can *show* the incoming
banner, but the dashboard Answer button has nothing to invoke — at best you could fire the
notification's full-screen answer `PendingIntent`, which is unreliable and version-fragile;
we don't recommend shipping it.

**Product consequence:** WhatsApp/Business calls are **display-only**; only **native
cellular** calls are answerable from the display. For a VoIP call banner the board's
Answer/Reject act as **dismiss** only.

### Optional: let the board hide the Answer button for VoIP calls
Today both call types send `category=1`. If you set a **flag bit** on VoIP-originated call
frames (e.g. reserved `flags` `bit3 = VOIP`), the board can suppress the Answer button for
calls it can't actually connect. Back-compatible (old board ignores the bit). Say the word
and we'll wire it.

---

## 7. Bottom line & action items

- Board wire contract, CRC, decode, routing, IPC, and banner: **all verified PASS.**
- Notifications now flow after the app-side preconditions were fixed.
- **Remaining, both app/platform-side:**
  1. **Add `com.whatsapp` to the allow-list** (only Business `com.whatsapp.w4b` gets through today) → `category=3`, `appName="WhatsApp"`. Confirm regular WhatsApp then relays.
  2. **WhatsApp calls are display-only** — no programmatic answer; only native cellular calls can be answered from the dashboard.
- **Open questions back to you:** (a) hide the Answer button for VoIP call banners (needs the §6 flag bit), or leave it as dismiss? (b) one shared WhatsApp identity, or a separate "WhatsApp Business" icon?

### Board source paths (for your byte-verification)
- `EVDISPLAY/ble-gatt/ble-gatt-server.c` — `crc16_ccitt` `:199`, downlink parse/CRC/decode/route `:718-752`, IPC writer `:327`, RX char props `:1090`
- `EVDISPLAY/ble-gatt/capnp/phone_notification.capnp.{c,h}` — codec (schema `0xb39c7a21e4d05f68`)
- `display_ui/DisplayUI_thema3/custom/custom.c` — `notif_ci_contains` `:749`, app→icon map `:1310`
- Authoritative wire spec (unchanged): `EVDISPLAY/bt-telemetry/capnpble.md` / `APP-NOTIFICATION-INTEGRATION.md`
