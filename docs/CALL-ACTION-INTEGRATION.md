# EVdisplay — Call Answer/End over BLE 0xAF05: board-side implementation notes

**Date:** 2026-07-08 (rewritten same day) · **Status:** board side DEPLOYED, phone side SHIPPED (per app team)

> ## ⚠️ The CANONICAL wire contract is the app team's spec:
> ## [`Fromappdeveloper/CALL-CONTROL-INTEGRATION.md`](Fromappdeveloper/CALL-CONTROL-INTEGRATION.md)
>
> The board firmware implements **that** document byte-exactly (`CallControl`,
> ctrlType `0x01`, 29-byte frame, golden CRC `0x1FBD`). An earlier board-drafted
> `CallAction` frame that briefly lived in this file was **replaced before any
> consumer existed** — if you have an old copy of this document, discard it; no
> code anywhere encodes or decodes the old 36-byte frame.

This file only records what the **board** does around that contract, plus the
current integration status.

## Board-side implementation (ble-gatt-server md5 `a0bbf01a…`, deployed 2026-07-08)

- Characteristic **`0xAF05`** (service `0xAF00`), **Notify + CCCD `0x2902`**;
  handles decl `0x000C` / value `0x000D` / CCCD `0x000E` (appended after `0xAF06`;
  existing handles frozen). **Phones must clear their GATT cache once** to
  discover it (toggle phone BT off/on or nRF "Refresh services").
- Rider presses **Answer / Reject / End** on the call banner → the LVGL UI writes
  `/var/run/call_action` (kv: `action=answer|reject`, `id`, `seq`, `ts_ms`;
  atomic tmp+rename; env `CALL_ACTION_PATH` overrides for tests).
- `ble-gatt-server` polls that file (~10 Hz connected / 5 Hz idle), consumes it
  (**read + unlink**), maps `answer→1`, `reject→2` (hangUp — used both for
  declining a ringing call and ending an active one), `dismiss→3`, and sends one
  `CallControl` frame, **retransmitted 3× total ~100 ms apart with the same
  `seq`** (phone dedups identical seq).
- Drops (logged to `/var/log/ble-gatt.log`, never queued): press older than
  **10 s**, **no central connected**, **`0xAF05` not subscribed**.
- `id` echoes the **contact-rich** notification id of the call when the board can
  tell the telecom dual-id pair apart (verified live: echoed `1729455722`, the
  contact record, not the generic `51729`). The phone should act on the current
  call regardless of `id` (canonical spec §5).
- Implicit ACK: the phone's updated `0xAF07` banner ("In call" / "Call ended") —
  the board UI understands both (call-session tracking, End button; see
  `APP-NOTIFICATION-INTEGRATION.md` §5e/§5f).
- Self-test: `ble-gatt-server --call-golden` prints the canonical spec's golden
  frame and byte-compares against it (exit 1 on mismatch). Verified identical on
  host and on the board.

## ⭐⭐ FINAL STATUS 2026-07-08 (~20:00) — BLE chain PROVEN over-the-air end-to-end; phone RECEIVES but does NOT ACT

The user cleared the phone's cache (forget/reboot) and re-tested. Session
`LE central connected 79:6A:F1:CC:C9:52` shows, in order (all in
`/var/log/ble-gatt.log`, per-PDU trace active):

1. **Real service discovery** — Read By Group Type (`RX op=0x10` ×3, iterating all
   services) + Find Information on **CCCD handle 0x000E** (`RX op=0x04 [04 0E 00 0E 00]`):
   the stale-cache problem is GONE.
2. **The app SUBSCRIBED to `0xAF05`** — `ble-gatt: CCCD call-action notify=1`.
   **The installed app build DOES implement CallControl discovery/subscribe.** ✅
3. **CallControl frames delivered over the air** during two real calls:
   ```
   CALL UPLINK action=reject(2) id=1729455722 seq=6 ... 29B x3 hex=AA1901...F28D
   CALL UPLINK action=answer(1) id=1729455722 seq=7 ... 29B x3 hex=AA1901...15AE
   CALL UPLINK action=reject(2) id=1729455722 seq=8 ... 29B x3 hex=AA1901...A82F
   ```
   Frame bytes match the golden encoding of your own `CallControlSchemaTest`
   (id=1729455722, seq, action all correctly placed; CRC valid).
4. **The phone did not act on any of them.** After `answer seq=7` the dialer kept
   re-posting "Incoming voice call" (no "In call" update = no implicit ACK), and
   both calls ended as **"2 missed voice calls"** — neither answered nor declined.

**Verdict: the board side and the BLE link are 100% proven. The remaining issue is
inside the app's receive→act path — exactly the "received, logged, and silently
ignored" failure modes your own spec §4 lists:**
- "Call control" runtime permission (`ANSWER_PHONE_CALLS`) not granted, or
- the app's "Mirror to vehicle display" toggle disabled, or
- Android < 8 (answer) / < 9 (hangUp) — unlikely, or
- the build's decoder/action wiring — check `adb logcat -s NotifRelay` while
  pressing Answer on the dashboard during a ring: your spec says it logs
  `call-control: action=1 seq=… → answered` on success, `bad frame` /
  `duplicate seq` / an ignore reason otherwise. That logcat line is the next
  piece of evidence needed, and it is entirely on the phone.

**Follow-up session (~20:40, central `52:8C:85:58:49:28`) — Reject retested, same
result, plus a likely ROOT CAUSE candidate.** Two more rings; both Reject presses
were delivered byte-perfect (`action=2`, correct seq/id, CRC valid):
```
CALL UPLINK action=reject(2) id=1729455722 seq=9  ... 29B x3 hex=AA1901...6A66156709000200 1C59
CALL UPLINK action=reject(2) id=1729455722 seq=10 ... 29B x3 hex=AA1901...6A6615670A000200 C0C2
```
No decline happened; the ring re-posts continued and both calls ended
`"Call ended"` → WhatsApp `"Missed voice call"` / `"2 missed voice calls"`.
Answer (seq=7, earlier session) behaves identically — **neither action works; this
is NOT reject-specific.**

⚠️ **Likely root cause — the test calls are WhatsApp VOICE calls, not cellular:**
every missed call in these sessions logs under **WhatsApp** ("Missed voice call"),
i.e. the incoming call is a third-party **ConnectionService (VoIP)** call surfaced
through Telecom. Your spec §4 implements actions via
`TelecomManager.acceptRingingCall()` / `endCall()` — on modern Android these
generally control only the default phone account's cellular call and **do NOT
answer/reject third-party VoIP calls** (and both are deprecated/restricted from
API 28+). If that is your implementation, WhatsApp calls will be received and
silently no-op — exactly what we observe.
**Two-part check:**
1. Re-run the test with a **regular SIM/cellular call**. If Answer/Reject work
   there, the diagnosis is confirmed.
2. For VoIP (WhatsApp/Telegram) calls you need an **`InCallService`**
   implementation (`Call.answer()` / `Call.reject()`/`disconnect()` on the actual
   `Call` object) — an InCallService receives third-party ConnectionService calls
   too when the user grants it; `TelecomManager` one-shots do not reach them.

---

## Earlier diagnosis (superseded by the above, kept for history): stale GATT cache, discovery never re-ran

**Latest live call test (session `LE central connected 42:5D:AE:7D:65:45`, MTU 247):**
the entire notification pipeline worked — dual-id call rings (`cat=1 flags=0x01`),
"Call ended" re-posts, WhatsApp missed-call counters up to "7 missed voice calls" —
and every Answer/End press was consumed and dropped, 5 presses across the calls:

```
ble-gatt: CALL action=reject id=1729455722 seq=1 dropped (0xAF05 not subscribed)
ble-gatt: CALL action=answer id=1729455722 seq=2 dropped (0xAF05 not subscribed)
ble-gatt: CALL action=reject id=1729455722 seq=3 dropped (0xAF05 not subscribed)
ble-gatt: CALL action=answer id=1729455722 seq=4 dropped (0xAF05 not subscribed)
ble-gatt: CALL action=reject id=1729455722 seq=5 dropped (0xAF05 not subscribed)
```

`CCCD call-action notify=1` has **never** appeared in any session. To find out WHY the
app never subscribes, we enabled the server's per-PDU trace and captured the phone's
next reconnection (central `69:29:03:E3:67:DE`, immediately after the session above).
The phone sent **exactly three ATT PDUs** before settling in:

```
RX op=0x08 [08 01 00 FF FF 01 2A]   <- Read By Type: GAP Appearance (0x2A01) — stack preamble
RX op=0x03 [03 05 02]               <- Exchange MTU Response (client MTU 517)
RX op=0x12 [12 07 00 01 00]         <- Write Request: handle 0x0007 = TELEMETRY (0xAF08) CCCD := notify
```

**No Read By Group Type, no characteristic discovery, no Find Information — the phone
never performed service discovery** (across the whole traced window: zero `0x10` and
zero `0x04` PDUs). It wrote handle `0x0007` directly, which it can only know from its
**cached attribute table**, cached before `0xAF05` existed. So:

- The app's capability rule ("char absent → skip, feature off") is operating on a stale
  table → `0xAF05` is invisible to it → no subscribe → board (correctly) drops presses.
- **The installed app build is neither convicted nor cleared** — on a stale cache a
  fully CallControl-capable build behaves exactly like this. Verdict requires one
  connection with fresh discovery.
- A phone **BT off/on toggle did NOT clear the cache** on this phone (the trace above is
  from a post-toggle reconnect). Escalation ladder to force re-discovery, in order:
  1. **Forget/unpair "EVdisplay"** in phone Bluetooth settings (if listed), then reconnect;
  2. **Reboot the phone**;
  3. nRF Connect → connect to EVdisplay → ⋮ → **"Refresh services"**, disconnect, then use the app;
  4. Sledgehammer: Settings → Apps → Bluetooth → **Clear storage/cache**, reboot.
  (App-side alternative: request `BluetoothGatt#refresh()` via reflection after connect,
  or bump something that invalidates the cache — Android only auto-refreshes reliably
  when the peripheral implements the GATT Service Changed indication, which this
  open/unbonded firmware does not.)
- The board's per-PDU trace is LEFT ENABLED until the next board power-cycle: the next
  fresh-discovery session will show, black-on-white, whether the app then writes CCCD
  handle `0x000E` (`CCCD call-action notify=1`). That single log line is the verdict on
  the app build.

Everything else in the same session worked: notifications (WhatsApp texts, call ring
dual-id `cat=1 flags=0x01`, "Call ended", missed-call summaries) decoded + bannered,
presses relayed to `/var/run/call_action` and consumed. Board side has nothing left to do.

## What the app team must do / check (in order)

> Steps 1–2 are ✅ **DONE/PROVEN** as of the final status above (fresh discovery +
> `0xAF05` subscribe + frames delivered). **The open item is step 3.** Step 1 is
> kept because every OTHER phone with an old cache will hit the same wall — the
> programmatic refresh remains strongly recommended.

1. **Get the phone to re-discover the GATT database.** The test phone reuses its
   cached table on every reconnect (proof above) and a BT off/on toggle did NOT
   clear it. Options, strongest first: forget/unpair "EVdisplay" in phone BT
   settings; reboot the phone; nRF Connect → "Refresh services". **Programmatic
   option for your app (recommended, fixes every user at once): call the hidden
   `BluetoothGatt#refresh()` (reflection) after connecting, or re-run discovery
   whenever your cached table lacks `0xAF05`** — the firmware does not implement
   the GATT Service Changed indication (open GATT, no bonding), so Android will
   not refresh on its own.
2. **Confirm the installed build actually implements CallControl** per your own
   `CALL-CONTROL-INTEGRATION.md` ("subscribes to `0xAF05`'s CCCD right after the
   `0xAF08` subscribe"). We cannot verify this from the board until a session
   with real discovery happens — the board's PDU trace is enabled and will show
   it immediately.
3. Once subscribed, if presses still don't control the call: check the
   **"Mirror to vehicle display" toggle** and the **Call-control runtime
   permission (`ANSWER_PHONE_CALLS`)** on the phone (your spec §4: events are
   otherwise received, logged, and silently ignored); answer needs Android 8+,
   hangUp Android 9+.

## Acceptance test (one call, one look at the board log)

`ssh root@192.168.42.1` (pw `root`), `tail -f /var/log/ble-gatt.log`, connect the
app, ring the phone, press Answer on the dashboard. PASS = these two lines:

```
ble-gatt: CCCD call-action notify=1                                  <- app subscribed 0xAF05
ble-gatt: CALL UPLINK action=answer(1) id=... seq=... -> 0xAF05 CallControl 29B x3 hex=AA1901...
```

...and the call answers on the phone (the app's `0xAF07` "In call" banner update
is the implicit ACK). **Status 2026-07-08 ~20:00: both log lines PASS on the live
board (seq 6/7/8 delivered); the call did NOT answer — the gap is between the
app's BLE receive and its telecom action. `adb logcat -s NotifRelay` during the
next test call is the decisive next evidence.**

## ⭐ STATUS UPDATE 2026-07-08 ~21:00 — Reject WORKS, Answer does NOT

Live user test result: **`action=2` (hangUp/reject) now works end-to-end** — a
dashboard Reject press declines the ringing call on the phone. This proves the
ENTIRE chain (board button → BLE `0xAF05` CallControl → app receive → telecom
action) for action 2. **`action=1` (answer) is still ignored** — frames are
delivered identically (earlier sessions byte-verified answer frames on the wire,
e.g. seq=7: `action=0x01`, valid CRC) but the call never answers.

Because reject works in the same sessions, the asymmetry is in the app's ANSWER
code path, not transport. Known Android platform cause: `TelecomManager.
acceptRingingCall()` is far more restricted than `endCall()` (deprecated since
API 28, needs default-dialer-level standing on many OEM builds, and does not
answer third-party VoIP calls). **Recommended fix: implement an `InCallService`
and act on the live `Call` object with `Call.answer(videoState)` / `Call.reject()`**
— this also makes both actions work uniformly for WhatsApp/VoIP calls.

Decisive evidence to collect app-side: `adb logcat -s NotifRelay` while pressing
Answer on the dashboard during a ring — per your spec it logs the action taken
or the reason ignored.

## ✅ PHONE-SIDE RESOLUTION 2026-07-09 — Telegram answer/reject fixed (WhatsApp already worked)

**Symptom (app team):** answering/rejecting a **Telegram** call from the cluster
did nothing, while **WhatsApp** worked end-to-end.

**Root cause — phone-side classification, not transport.** The app answers/rejects
a VoIP call by firing the call notification's own Answer/Hang-up `PendingIntent`
(the only path that reaches a third-party `ConnectionService` call —
`TelecomManager` can't, matching the board team's earlier diagnosis). Those intents
are only captured when the app recognizes the notification as a call. The
recognizer (`NotificationClassifier.isCall`) keyed on `Notification.CATEGORY_CALL`
or a dialer package:

- **WhatsApp** tags its incoming-call notification with `CATEGORY_CALL` (uses
  `Notification.CallStyle`) → recognized → Answer/Hang-up intents captured → board
  Answer fires WhatsApp's own answer action → **works**.
- **Telegram** does **not** set `CATEGORY_CALL` (classic full-screen intent +
  `addAction` "Answer"/"Decline") and isn't a dialer package → **not** recognized →
  intents never captured → on a board Answer the app fell through to
  `TelecomManager.acceptRingingCall()`, which no-ops on a VoIP call → **silently
  ignored**. (Telegram calls also mis-rendered as a transient *message* banner,
  category 3, instead of a persistent call banner.)

**Fix (`NotificationClassifier.isCall`):** additionally treat a notification as a
call when it comes from an allow-listed VoIP app (WhatsApp/Telegram) **and carries
a full-screen intent** — the signal unique to an incoming-call ring. Telegram's
Answer/Hang-up intents are now captured and fired through the same path WhatsApp
already used; Telegram calls now also render as a proper persistent call banner
(category 1, ongoing) with a "Call ended" collapse. No board or wire change.

**Confirm on-device** (`adb logcat -s NotifRelay` during a Telegram ring):
`call actions captured pkg=org.telegram.messenger answer=true hangUp=true`, then
`call-control: action=1 … → answered (notification action)` and the call connects.

**Known residual limitation:** if a future Telegram build ships an incoming-call
notification with **no action buttons** (full-screen UI only), there is nothing to
fire and answer would still fail — that case alone would need an `InCallService`
(deliberately avoided: it requires default-dialer standing, inappropriate for a
cockpit app). The common case (Telegram exposes Answer/Decline actions) is fixed.

## Board sources

- Server: `EVDISPLAY/ble-gatt/ble-gatt-server.c` (`check_call_action`,
  `encode_call_control`, `frame_call_control`, `--call-golden`).
- Schema transcription: `EVDISPLAY/bt-telemetry/call_control.capnp`.
- UI buttons/banner: `display_ui/mid_800x480/custom/custom.c` (call session,
  `call_btn_cb`, `call_action_write`).
