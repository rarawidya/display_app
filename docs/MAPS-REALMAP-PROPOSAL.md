# EVdisplay — Real "Google-Maps-like" Live Map: Audit & Costed Proposal

**Date:** 2026-07-08 · **Audience:** product owner + Android team + firmware
**Goal:** board display looks like Google Maps (real streets, live position), touch
works, suitable for an EV motorcycle.

> **DECISION MADE (owner, 2026-07-08) — this doc is now the costing/rationale
> record.** Option 4 (RouteChunk) is **SHIPPED and verified live end-to-end the same
> day**; Option 3 (phone-rendered viewport stream) is **CHOSEN** for the real-map
> phase — spec in [`MAP-STREAM-INTEGRATION.md`](MAP-STREAM-INTEGRATION.md); Option 1
> is fallback-only; Option 2 is rejected (also license-blocked: MapTiler terms
> prohibit storing/exporting tiles for use outside direct API access, so no tiles —
> live or baked — on the board). One-page summary for the app team:
> [`README-MAPS-HANDOFF.md`](README-MAPS-HANDOFF.md).

---

## Part A — What the SG2002 hardware/firmware can and cannot do (measured 2026-07-08)

| Item | Reality (verified on the live board / source tree) |
|---|---|
| CPU | 1x T-Head C906, RV64 `imafdvcsu` (FPU + vector), ~1 GHz, **no GPU / no 2D blitter in our display path** — every pixel is CPU-blitted (render 800x480 → software 1.6x upscale + 90° rotate to the 800x1280 panel) |
| Measured graphics ceiling | Static screens smooth; **any full-screen motion drops frames** (the 180 ms screen slide is visibly choppy = ~1 M px/frame CPU pipeline). Budget realistic map motion at **2–5 fps**, small-region updates at 12.5 fps |
| RAM | **224 MB total, ~163 MB free** at runtime (UI uses ~13 MB) — plenty for tile caches/canvases; LVGL internal heap is only 320 KB but can be raised |
| Storage | SD card **30.5 GB**; rootfs uses only 340 MB — **~29 GB unpartitioned** → room for a full offline tile pack |
| Wi-Fi | AIC8800: **station (managed) mode IS supported by the driver** (`iw phy`), currently runs as AP (hostapd present). **wpa_supplicant is NOT in the image** (would need adding for STA). Board AP + phone-connects-to-board works today |
| TLS | **OpenSSL 3 libs ARE in the image** (`libssl.so.3`) — a small HTTPS fetcher is possible; busybox wget has no TLS; no curl |
| Image decoders | LVGL 9.3 bundles PNG (lodepng) + JPEG (tjpgd) decoders, currently **compiled out** — enabling is a config flip + rebuild. 800x340 JPEG decode ≈ 100–300 ms/frame on this CPU (2–4 fps sustained) |
| Touch | GT911 is multitouch hardware, but LVGL's evdev driver **collapses it to ONE pointer** → drag/pan works (shipped), **pinch needs a custom MT indev + gesture layer (~1–2 sessions)** |
| BLE | MTU 247, realistic sustained ~10–20 KB/s → **fine for data (nav frames, polylines), useless for live imagery** |
| EV-motorcycle context | Sunlight/glanceability favors bold, simple rendering; vibration argues against heavy sustained SD writes; riders mostly need **next turn + route shape + position**, not map browsing |

**Bottom line:** this hardware can absolutely show a live real-street map — as
**pre-rendered raster imagery at a few fps** — but can never run a vector map engine
(Mapbox-GL-class) and can't do buttery 30 fps map motion.

---

## Part B — The four realistic paths, costed

### Option 1 — Offline tile pack on the SD card
Pre-rasterize OpenStreetMap for East Java (z12–z16 ≈ 150–200k tiles ≈ **2–6 GB**,
fits the free 29 GB), new SD data partition, board composites 256px raster tiles,
GPS lat/lon appended to the BLE schema (phone streams position).
- **Cost:** **$0 licensing** if self-generated from OSM (ODbL attribution required). MapTiler's offline data packages are an alternative but are enterprise-priced (per-device licensing, contact sales) — not needed.
- **Firmware:** tile compositor + decoder enable + GPS schema + partition/tooling ≈ **2–3 weeks**. Touch pan across a tile grid is feasible (blit-limited, ~5 fps); zoom = switching tile levels (no scaling → actually cheaper than today's transform).
- **Pros:** no connectivity needed while riding; real streets. **Cons:** map ages (periodic pack refresh), biggest firmware effort, board look ≠ phone app look.

### Option 2 — Live online tiles (MapTiler API key) via phone hotspot
- **Cost:** the free tier (100k requests/mo) is **non-commercial only** — a product needs [MapTiler Flex **$25/month**](https://www.maptiler.com/cloud/pricing/) (+$0.10/1k extra requests) or Unlimited **$295/month** for fleets. Per single bike, Flex is ample.
- **Firmware:** add wpa_supplicant + STA-mode switching, HTTPS fetcher (OpenSSL exists), decoder enable, tile cache ≈ **3–4 weeks**, plus the GPS schema.
- **Cons:** the weakest link is the product story — the bike's map dies whenever the phone hotspot/handover flakes mid-ride; most moving parts of any option. **Not recommended.**

### Option 3 — Phone renders the map, board displays it (RECOMMENDED for "same as gmaps")
The Android app **already renders a MapTiler map**. It connects to the **board's
existing Wi-Fi AP** and streams its rendered map viewport as JPEG frames
(800x340 @ quality ~70 ≈ 30–60 KB) to a tiny receiver daemon on the board at **1–3 fps**
(≈ 60–180 KB/s — trivial over Wi-Fi; measured-impossible over BLE). Touch gestures on
the board (drag/zoom taps) are relayed back over the same socket; the phone applies
them and the next frames reflect it.
- **Cost:** **$0/month for the board — no API key on the board at all.** (The app's own MapTiler plan covers rendering; a commercial app needs Flex $25/mo regardless of the board.)
- **Firmware:** JPEG decoder enable + Wi-Fi frame receiver + LVGL canvas blit + touch relay ≈ **1.5–2 weeks**. CPU: ~20–30% for 2 fps decode+blit — fits.
- **App team:** render-to-bitmap + JPEG encode + stream + apply relayed gestures ≈ 1–2 weeks.
- **Pros:** pixel-identical to the phone (true gmaps look, real position, live traffic if the app shows it), cheapest recurring cost, no board connectivity to the internet. **Cons:** map only live while the phone app runs and is on the board's Wi-Fi; 1–3 fps (fine for riding glances).

### Option 4 — RouteChunk (baseline, do this regardless)
Phone sends the true route polyline over BLE (already in the frozen schema); board
draws the real route shape and the live position dot on today's styled map.
- **Cost: $0.** **Firmware ≈ 1–2 sessions** (server decode + UI polyline render — machinery mostly exists). App: emit RouteChunk (golden frame already in NAVIGATION-INTEGRATION.md).
- Delivers a truthful moving position on real route geometry — everything a rider needs — without any of the above.

---

## Recommendation

1. **Now / free:** ship the Priority-1 heartbeat (app) + **Option 4 RouteChunk** — real route shape + real live position within days, $0.
2. **For the true "same as Google Maps" target: Option 3** (phone-rendered viewport over the board's own Wi-Fi). One-time engineering (~2 weeks firmware + ~2 weeks app), **$0/month on the board**; the only recurring cost in the whole system is the app's own MapTiler plan (**Flex $25/month** for a commercial app — this is the "pay something" and it pays for the phone side, not the board).
3. **Fallback** (if phone-dependency while riding is unacceptable): Option 1 offline OSM tile pack — $0 licensing, ~2–3 weeks firmware, plus the GPS schema addition.
4. **Skip** Option 2 (board-side online tiles) — most cost, most fragility, least benefit.

**Decision needed from the owner:** greenlight Option 4 now (free), and choose
Option 3 vs Option 1 for the real-map phase; if Option 3, confirm the app's MapTiler
commercial plan (Flex $25/mo) with the app team.

*Pricing source: [MapTiler Cloud pricing](https://www.maptiler.com/cloud/pricing/), checked 2026-07-08.*
