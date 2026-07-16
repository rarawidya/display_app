# EVdisplay — Clock / Time Sync: App-Team Response (v1)

**Re:** your `TIME-SYNC-INTEGRATION.md` (board side implemented 2026-07-15).
**From:** app developer team (InnoRide Android cockpit).
**Status:** **App side implemented and verified on hardware (2026-07-16).** We send
`TIME_SYNC` exactly to your §"What to send" contract — `category=32`, `title="TIME_SYNC"`,
newline `key=value` body on `0xAF07`, no new schema/characteristic. This confirms the
wire, records what we actually transmit, and raises **two items that need your input**
(§4). Nothing here asks you to change your frame format.

---

## 1. What the app sends — exact frame

A `PhoneNotification` (same frozen encoder as `ODO_RESET_TRIP` / `WIFI_*`) framed as
`[0xAA][LEN][capnp payload][CRC16-CCITT-LE]` in **one** acknowledged write to `0xAF07`:

| field | value |
|---|---|
| `category` | `32` (control command) |
| `appName` | `"EVD"` |
| `title` | `"TIME_SYNC"` |
| `body` | `epoch=<unix seconds, UTC>\ntz_offset_min=<signed minutes>` |
| `id` / `flags` / `timestampUnix` | `0` / `0` / `0` (unused for this command) |

- `epoch` = `System.currentTimeMillis() / 1000` (already UTC).
- `tz_offset_min` = `TimeZone.getDefault().getOffset(now) / 60000` — **always sent**
  (we don't treat it as optional), signed, so the board renders local time.
- Body is **two lines** separated by a single `\n` (`0x0A`), no trailing newline,
  `epoch` first then `tz_offset_min`. UTF-8, ASCII digits + `=` + `-` only.

**Example body** we actually put on the wire (WIB / UTC+7):
```
epoch=1784174555
tz_offset_min=420
```

## 2. Verified on hardware (Samsung SM-A546E → board, 2026-07-16)

App logs at the moment of send — the `0xAF07` write was **ACKed by the controller**
(with-response GATT write, `onCharacteristicWrite` success = `delivered`):

```
NotifRelay: writing 0xAF07 frame len=124 id=0 cat=32
NotifRelay: push id=0 cat=32 flags=0x00 → delivered
TimeSync:   TIME_SYNC epoch=1784174555 tz_offset_min=420 -> sent
```

`epoch=1784174555` decodes to **2026-07-16 04:02:35 UTC** = 11:02:35 WIB, matching the
phone's log timestamp; `tz_offset_min=420` = UTC+7. Total frame = **124 bytes** on
`0xAF07`. (We have no board-side readback yet — see §4.1 — so "delivered" here means the
**BLE layer** confirmed receipt, not that we observed the on-screen clock. Please confirm
your side logged a successful `settimeofday` for this frame.)

## 3. When the app sends (matches your §"When to send")

| Trigger | Implementation |
|---|---|
| **On every BLE (re)connect** | Fires the instant `connectionState` → `CONNECTED`, which the app sets **only after** the GATT session is fully ready (services discovered + CCCD subscribed + DIS read). So the sync lands right after handshake, before the user sees a stale 2018 clock. Also re-fires after any watchdog-driven reconnect. |
| **Periodically while connected** | Every **5 min** (`RESYNC_INTERVAL_MS`). Loop is bound to the connected state — a disconnect cancels it, the next connect restarts it. |
| **On timezone / DST / manual clock change** | A receiver for `ACTION_TIMEZONE_CHANGED` + `ACTION_TIME_CHANGED` resends immediately with the new offset. |

No app-level ACK is expected (per your §"Board behaviour") — we rely on the BLE write
result for delivery and on correct on-screen time for end-to-end success.

## 4. ⭐ Two items that need your input

### 4.1 We'd like a clock **read-back** field on telemetry (optional, future)

Today success is only observable as "the screen looks right." A tiny addition to
`VotolTelemetry` would let us **close the loop automatically** and alert on drift/failure:

```capnp
boardEpoch @NN :UInt32;   # board's current UTC unix seconds (0 = clock never set)
```

Append-only at the next free ordinal (our decoder honors `LEN` and ignores trailing
fields, so this is non-breaking). With it we can verify each `TIME_SYNC` actually took,
show a "clock synced ✓/✗" state, and detect a board that rejected our epoch. **Not
required for v1** — flagging as the natural next step. Let us know the ordinal if you add it.

### 4.2 ⚠️ Gating: `TIME_SYNC` currently follows the "Mirror to vehicle display" toggle

Per product decision, the app **gates `TIME_SYNC` on the same user setting that gates
call/notification mirroring** (`notificationRelayEnabled`). Consequence you should know:

- Toggle **ON** (default for users who want mirroring): behaves exactly as your doc — sync
  on connect + every 5 min + on tz change. ✅
- Toggle **OFF**: the app sends **no `TIME_SYNC`**. After a board reboot the clock stays at
  ~2018 until the user enables mirroring. The board is unaffected by this — it just never
  receives a frame.

**Question for you:** is that acceptable, or do you consider a correct clock a baseline
board function that should sync **regardless** of the notification-mirroring preference?
If the latter, we can ungate `TIME_SYNC` (one-line change) so the clock is always set on
connect while notifications stay behind the toggle. **Our recommendation: ungate it** —
the clock isn't really a "notification." Awaiting your call.

## 5. Contract confirmations we're assuming (please sanity-check)

1. Body parser splits on `\n`, `key=value` per line, **order-independent**, and tolerates
   `tz_offset_min` always being present. ✅ expected.
2. `epoch` range-validated (2023-11 … 2096); stale/`0`/garbage rejected + logged, no clock
   change. ✅ per your §"Board behaviour".
3. Negative `tz_offset_min` (west of UTC, e.g. `-300`) is parsed as signed. ✅ we send signed.
4. One `TIME_SYNC` per ATT write, payload well under the 240 B board RX cap (our verified
   frame is 124 B). ✅.

---

*App developer team — 2026-07-16. Transport details unchanged from `TIME-SYNC-INTEGRATION.md`
/ `capnpble.md §5b`. App impl: `TimeSyncCommand` + `TimeSyncCoordinator`, verified by
`TimeSyncCommandTest`.*
