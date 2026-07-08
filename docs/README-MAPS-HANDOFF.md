# EVdisplay Maps — Decision & Handoff (READ THIS FIRST)

**Date:** 2026-07-08 · **For:** the Android app team · **From:** firmware (EVdisplay board)

This one-pager is the final architecture decision for maps/navigation on the board.
The two documents you implement against are
[`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md) (BLE nav data — **live**) and
[`MAP-STREAM-INTEGRATION.md`](MAP-STREAM-INTEGRATION.md) (map viewport streaming —
**planned**). Everything else is background.

---

## 1. THE DECISION

**The phone renders the map (your existing MapTiler code, API key stays in the app)
and streams its rendered viewport to the board over the board's own Wi-Fi hotspot;
the BLE `0xAF06` navigation channel (heartbeat + RouteSummary + RouteChunk) stays as
the always-on baseline and automatic fallback.** Why: the board has no internet and
no GPU (it can neither fetch tiles nor run a vector renderer), MapTiler's terms
prohibit storing/redistributing tiles inside a device, and the BLE nav pipeline is
already proven live end-to-end with your app — so the phone does the map rendering it
already does, and the board displays it.

## 2. IMPLEMENT NOW (priority order)

### Priority 1 — NavInstruction heartbeat + 3 field fixes (small, unblocks "real-time" today)
Spec: `NAVIGATION-INTEGRATION.md`, the **⭐ ACTION REQUIRED** block.
- Stream `NavInstruction` **≤1 Hz moving / 0.2 Hz stationary / immediately on
  maneuver-or-state change** (today the app sends one burst at route creation, then
  silence — the board correctly declares NAV OFFLINE after 15 s).
- Fix `streetName` (send the road you turn **onto**, not the maneuver text),
  `destinationName` (currently empty), `seq` (roll +1 per frame).

**Good news first: RouteChunk already works end-to-end.** Your first real route
(29 points, Mojokerto) was received, decoded, and rendered on the board's map —
polyline, destination pin, and live position dot all correct **on the first try**
(verified live 2026-07-08). Nothing to change there; keep sending it.

### Priority 2 — Map-stream client ("looks exactly like the phone")
Spec: `MAP-STREAM-INTEGRATION.md`. The app renders its map viewport at 800x340,
POSTs JPEG frames at 1–3 fps to the board over the board's Wi-Fi AP (socket bound
via `requestNetwork`/`bindSocket` — Kotlin snippet in §2 of the spec), and applies
touch gestures relayed back over a WebSocket. **Board side is PLANNED, not built
(~2 weeks of firmware work once started)** — so sequencing is: ship Priority 1 now,
build the stream client against the curl-testable contract in the spec whenever you
like; it will light up when the firmware endpoint ships. BLE nav keeps working
underneath and is the automatic fallback when the stream stops.

## 3. REJECTED APPROACHES (settled — please don't re-propose)

- **MapTiler on the board** (live tiles or a baked static map in firmware): board has
  no internet, and MapTiler's terms prohibit storing/exporting/redistributing tiles
  outside direct per-user API access — license-blocked for device firmware. The API
  key never goes on the board.
- **Streaming map imagery over BLE:** 244 B per write, ~10–20 KB/s sustained —
  seconds per frame; imagery goes over Wi-Fi (Priority 2), data goes over BLE.
- **Offline tile pack on the board's SD:** kept only as a future fallback if
  phone-dependency while riding ever becomes unacceptable — and it would use
  OSM/Geofabrik data (ODbL, $0), **not** MapTiler. Not scheduled.

Full costing/rationale: [`MAPS-REALMAP-PROPOSAL.md`](MAPS-REALMAP-PROPOSAL.md).

## 4. COST

- **App side:** commercial use of MapTiler requires **Flex, ~$25/month** (the free
  tier is non-commercial). That is the only recurring cost in the whole system.
- **Board side:** $0 recurring — no API key, no internet, no cloud dependency.

## 5. VERIFYING AGAINST THE REAL BOARD (no special tooling)

`ssh root@192.168.42.1` (password `root`), then:

```sh
tail -f /var/log/ble-gatt.log     # every 0xAF06 write is hex-dumped + decoded here
ls -l --full-time /var/run/nav_state   # mtime must tick at your heartbeat rate;
                                       # maps screen goes LIVE within 1 s of frames
cat /var/run/nav_state            # the decoded fields the board renders
cat /var/run/nav_route            # your RouteChunk polyline (route_id / points / p= lines)
```

Heartbeat working = the maps screen stays LIVE (no "NAV OFFLINE" after 15 s) and the
distance/ETA count down smoothly. For the future map stream, the curl test recipe is
`MAP-STREAM-INTEGRATION.md` §8 (works from any laptop on the board's hotspot, before
any Android code).

---
*Questions → firmware team. Wire framing/CRC shared with the notification channel
([`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md)).*
