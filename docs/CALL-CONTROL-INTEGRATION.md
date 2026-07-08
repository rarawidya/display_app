# EVdisplay — Board→Phone Call Control Integration Spec (v1, PROPOSED)

**Date:** 2026-07-08 · **From:** Android app team · **To:** firmware (EVdisplay board)
**Status:** **phone side IMPLEMENTED** (subscribes, decodes, acts — ships in the current
app build); **board side NOT BUILT** — this doc is the contract to build it against.
The app lights up automatically once the characteristic exists (capability = GATT
discovery, same rule as navigation's `0xAF06`).

**Why:** the board's call banner (from [`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md))
renders **Answer / End buttons that currently do nothing** — every existing channel is
phone→board. This spec adds the missing **board→phone uplink** so a button tap on the
cluster answers or ends the call on the phone.

**Relationship to existing channels (all untouched):**

| Char | Direction | Contents |
|---|---|---|
| `0xAF08` NOTIFY | board→phone | `VotolTelemetry` — **frozen** |
| `0xAF07` WRITE | phone→board | `PhoneNotification` (banners + `category=32` commands) |
| `0xAF06` WRITE | phone→board | Navigation (`RouteSummary`/`NavInstruction`/`RouteChunk`) |
| **`0xAF05` NOTIFY — NEW** | **board→phone** | **Control events (this spec)** |

---

## 1. BLE transport

| Item | Value |
|---|---|
| Characteristic | **`0xAF05`** (16-bit), under service `0xAF00` |
| Properties | **NOTIFY (0x10)** + CCCD (`0x2902`) |
| Direction | board → phone only (the phone never writes it) |
| Delivery | **One event = one notification of one complete frame** (≤ 244 B at MTU 247; ours is 29 B) |
| Capability negotiation | GATT discovery. Phone subscribes to `0xAF05`'s CCCD right after the `0xAF08` subscribe, during the connect handshake. **Char absent (old firmware) → phone skips it, feature off, nothing else changes.** Board: only send when the CCCD is enabled (a subscriber exists) — old apps never enable it. |

---

## 2. Frame format

Identical wrapper to every other channel (same CRC path you already have):

```
[0xAA] [LEN] [ ctrlType(1) ‖ capnp payload : LEN-1 bytes ] [ CRC16 : 2 bytes LE ]
```

- `LEN` = `1 (ctrlType) + capnp size`.
- `ctrlType` — 1-byte message tag (mirrors the nav channel's `navType`):

  | `ctrlType` | Message |
  |---|---|
  | `0x01` | `CallControl` (this spec) |
  | `0x02+` | reserved for future board→phone events (media keys, brightness, …) |

- `capnp` — **unpacked, single-segment** Cap'n Proto message.
- `CRC16` — CRC16-CCITT (poly `0x1021`, init `0xFFFF`, no final XOR, MSB-first) over
  **`LEN` ‖ payload**, sent low byte first. The phone recomputes and drops on mismatch.

---

## 3. Cap'n Proto schema

```capnp
struct CallControl {
  id     @0 :UInt32;  # PhoneNotification.id of the banner the buttons belong to
                      # (echo back what the phone sent; 0 = "whatever call is current")
  seq    @1 :UInt16;  # rolling counter, +1 per USER ACTION (not per retransmit)
  action @2 :UInt8;   # 1 = answer · 2 = hangUp · 3 = dismissBanner (see §4)
}
```

Data section is one word — byte offsets (struct-relative; add 16 for
payload-absolute after the 8 B segment table + 8 B root pointer):

| Field | struct byte | size |
|---|---|---|
| id @0 | 0 | u32 |
| seq @1 | 4 | u16 |
| action @2 | 6 | u8 |
| (padding) | 7 | — |

Root pointer: `dataWords=1, ptrWords=0`. Total frame = **29 bytes**.

Ordinals are FROZEN once shipped; append new fields at @3+ (they grow `LEN`;
the phone honors `LEN` and ignores unknown trailing bytes — same rule as telemetry).

### Golden frame (byte-exact reference — validated against the app's decoder test)

`id=42, seq=1, action=2 (hangUp)`:

```
AA 19 01 00 00 00 00 02 00 00 00 00 00 00 00 01 00 00 00 2A 00 00 00 01 00 02 00 BD 1F
```

(29 bytes; `LEN=0x19=25`, `ctrlType=0x01`, CRC=`0x1FBD` → wire `BD 1F`.)
The app's `CallControlSchemaTest` asserts this exact frame decodes to those values —
use it as your encoder self-test.

---

## 4. Actions — what the phone does

| `action` | Board sends when | Phone behavior |
|---|---|---|
| `1` answer | rider taps **Answer** while a `category=1` + `flags.ongoing` banner shows | Accepts the ringing call (`TelecomManager.acceptRingingCall`). No-op if nothing is ringing. |
| `2` hangUp | rider taps **End** | Ends the active or ringing call (`TelecomManager.endCall`). Rejecting an incoming call is the same action. No-op if no call. |
| `3` dismissBanner | rider swipes/dismisses the banner locally | **No phone-side call action.** Informational — the phone may use it later to mark the notification as read. Board should clear its own banner locally; do not expect a reply. |

Phone-side requirements & caveats (already implemented, listed so firmware knows
the failure modes):

- Requires the user to have granted the **"Call control"** runtime permission
  (`ANSWER_PHONE_CALLS`) and enabled the app's *Mirror to vehicle display* toggle.
  Not granted → events are received, logged, and **silently ignored** — the board
  gets no error reply (there is none in v1; see §7).
- `answer` works on Android 8.0+ (API 26), `hangUp` on Android 9+ (API 28); on
  older phones the event is ignored.
- There is **no acknowledgement frame**. The board learns the outcome the same way
  the rider does: the phone's call state changes, and the app's existing
  `CallStateRelay` immediately pushes the updated banner over `0xAF07`
  (`In call` on answer, transient `Call ended` on hang-up). **That `0xAF07` update
  is your effective ACK** — expect it within ~1 s of a successful action.

---

## 5. Send rules (board side)

- Send **one frame per button tap**, `seq` +1 per tap.
- Notifications are unacknowledged — you MAY retransmit the same frame (same `seq`)
  up to 2 extra times ~100 ms apart if you want belt-and-braces delivery. The phone
  **dedups on `seq`** (a repeat of the last seen `seq` is dropped), so retransmits
  are always safe.
- `seq` starts anywhere, wraps at 16 bits; only "identical to the previous frame's
  seq" is treated as a retransmit.
- Don't queue taps while disconnected — stale call actions are worse than dropped
  ones. If the link is down, drop the event and let the rider use the phone.
- Populate `id` with the `PhoneNotification.id` of the currently shown call banner
  (you already track it for latest-wins). The phone currently acts on the *current*
  call regardless of `id` — it is there for logging/future multi-call handling.

---

## 6. How to verify end-to-end

**Board encoder self-test:** produce the §3 golden frame from `id=42, seq=1,
action=2` — byte-exact, CRC `BD 1F`.

**Live (phone connected, app's relay toggle ON, Call-control permission granted):**

1. Call the phone from another number → board shows the call banner (existing path).
2. Tap **Answer** on the board → send `action=1`.
   - Phone answers within ~1 s; board receives an `0xAF07` banner update (`In call`).
   - App-side log line to watch: `adb logcat -s NotifRelay` →
     `call-control: action=1 seq=… → answered`.
3. Tap **End** → send `action=2` → call ends; board receives transient `Call ended`
   banner (auto-expires ~12 s).
4. Retransmit test: send the same frame twice — the log shows the second dropped
   as `duplicate seq`.
5. Negative test: send with a corrupted CRC — phone logs `bad frame` and ignores.

---

## 7. Explicitly out of scope for v1 (say if you need them)

- **ACK/error frames** (e.g. "no call to answer", "permission missing") — the
  `0xAF07` banner update is the implicit ACK. If the bench shows this is not
  enough, v2 can add a phone→board result on `0xAF07` (`category=32` control) or
  a `ctrlType=0x02` reply — tell us which you'd prefer.
- **Mute / speaker / volume** during a call.
- **Media controls** (play/pause/skip) — natural `ctrlType=0x02` candidate later;
  the framing above already leaves room.
- Multi-call juggling (hold/swap) — `id` is in the schema so this stays possible.

---
*Owner: mobile + firmware · Framing/CRC shared with all existing channels ·
Companions: [`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md),
[`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md).*
