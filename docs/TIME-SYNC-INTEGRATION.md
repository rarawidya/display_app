# EVdisplay — Clock / Time Sync Integration (app → board)

**From:** firmware/board team (SG2002). **Status:** board side **implemented** in
`ble-gatt-server` (2026-07-15). This asks the app to send the phone's wall clock
so the dashboard shows the correct time.

## Why
The SG2002 has **no battery-backed RTC** — every boot it starts at **~2018**, so
the on-screen clock is wrong until something sets it. There is no NTP on the
board. **The phone is the time source.**

## What to send — `TIME_SYNC` (reuses the existing `category = 32` channel)
Exactly the same transport/pattern as `OTA_INSTALL` / `WIFI_*` (see `capnpble.md
§5b`): a `PhoneNotification` on **`0xAF07`** with `category = 32`, the command in
`title`, and newline `key=value` lines in `body`. **No new schema/characteristic.**

| field | | |
|---|---|---|
| `category` | `32` | control command |
| `title` | `TIME_SYNC` | |
| `body` | `epoch=<unix seconds, UTC>` | **required** — the phone's current wall clock, seconds since 1970 **UTC** |
| | `tz_offset_min=<signed minutes>` | **optional** — phone's UTC offset in minutes (e.g. `420` = UTC+7 WIB, `-300` = UTC−5) |

**Example body** (East Java / WIB, `2026-07-15 18:30:00` local = `11:30:00Z`):
```
epoch=1784219400
tz_offset_min=420
```

## When to send
1. **On every BLE connect**, right after the GATT session is ready (the board may
   have just booted to 2018 — sync it immediately).
2. **Periodically** while connected — every ~5 min is plenty (keeps drift out; the
   board has no other time source).
3. **On timezone/DST change** on the phone (resend with the new `tz_offset_min`).

Use `System.currentTimeMillis() / 1000` for `epoch` (already UTC). For
`tz_offset_min` use the phone's current UTC offset in minutes
(`TimeZone.getDefault().getOffset(now) / 60000`).

## Board behaviour (what you can rely on)
- `epoch` → the board sets its **UTC system clock** (`settimeofday`). Validated to a
  sane range (2023-11 … 2096); `0`/garbage/stale values are rejected (logged, no
  change).
- `tz_offset_min` → published on the board for the local-time display and written to
  `/etc/TZ` (POSIX). The on-screen clock then renders **local** time.
- **No ACK frame.** The implicit confirmation is that the board's reported time
  becomes correct. (If we later add a clock field to telemetry you can read it
  back; for now, correct on-screen time = success.)
- **Not persistent across reboot** (no RTC battery) — that's why you resend on every
  connect. This is by design; the phone is always the source of truth.

## Notes / relationship to notifications
- You **already** send the phone's wall clock in every `PhoneNotification`
  (`timestampUnix @1`). `TIME_SYNC` is the **explicit, deterministic** path — sent
  on connect so the clock is right immediately, not only when a notification
  happens to arrive.
- `TIME_SYNC` with a bad/short body is safely ignored (no clock change), like any
  malformed control command.

## Minimal app checklist
- [x] On BLE connect: send `TIME_SYNC{epoch, tz_offset_min}`.
- [x] Timer: resend every ~5 min while connected.
- [x] On tz/DST change: resend.
- [x] `epoch` = `System.currentTimeMillis()/1000` (UTC); `tz_offset_min` =
      `TimeZone.getDefault().getOffset(now)/60000`.

**App implementation (2026-07-16).** `TimeSyncCommand` builds the frozen control
frame (`category=32`, `title="TIME_SYNC"`, `body="epoch=…\ntz_offset_min=…"`);
`TimeSyncCoordinator` (app-scoped, `AppContainer.timeSyncCoordinator`, started in
`DisplayApp.onCreate`) observes `VehicleRepository.connectionState` and syncs on every
`CONNECTED` transition, then every ~5 min while connected (loop lives in
`collectLatest`, so a disconnect cancels it and the next connect restarts it), plus a
`ACTION_TIMEZONE_CHANGED`/`ACTION_TIME_CHANGED` receiver. Sends via the existing
`PhoneNotificationSender` → `0xAF07`. Gated on the user's "Mirror to vehicle display"
toggle (`AppSettings.notificationRelayEnabled`) — the same switch as the call/
notification relays; with mirroring off nothing is sent, and flipping it on while
connected syncs immediately. Covered by `TimeSyncCommandTest`.

*Firmware/board team — 2026-07-15. Transport details: `capnpble.md §5b`.*
