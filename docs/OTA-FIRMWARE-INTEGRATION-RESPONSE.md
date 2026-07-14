# EVdisplay — OTA Firmware Update: Firmware-Team Response (v1)

**Re:** your `Fromappdeveloper/OTA-FIRMWARE-INTEGRATION.md` (proposal v0).
**From:** firmware/board team (SG2002).
**Status:** answers to your ⭐ open items so we can freeze a v1 contract. The
board side is **already implemented to your §5a frame** (built, staged dormant —
not yet enabled on hardware; see §4 below). Nothing here changes your §5a wire
format; it confirms it and picks the open options.

---

## 1. Answers to your open-items table (your §9)

| # | Your question | **Our answer (v1)** |
|---|---|---|
| 1 | Target: board / VOTOL / both? | **Board only.** And specifically the **display app binary** (`/usr/bin/lvgl_evdash`), *not* a full board/rootfs image (see §2). VOTOL controller = separate, later target. |
| 2 | Transport: network-pull (Option A) confirmed? BLE-push needed? | **Option A confirmed.** Board pulls over Wi-Fi STA (joins your hotspot) and verifies locally. **Option B (BLE-push) not needed** — skip it. |
| 3 | Version source: `fwVersion@15` on telemetry, or read characteristic? | **`fwVersion` appended to `VotolTelemetry`** as packed semver `UInt32` (`(major<<16)|(minor<<8)|patch`) — **at `@17`, not `@15`** (see the ⚠️ collision note below §1). ✅ **NOW LIVE on the wire** = V1.0.0 (see §3). |
| 4 | On-device mechanism: A/B + rollback? swupdate/RAUC/custom? | **Custom, single-partition, atomic swap + auto-revert.** Because v1 swaps one binary (not a partition image): download to `lvgl_evdash.new`, back up current to `lvgl_evdash.pre-ota.<ver>`, `ota_pending` sentinel, atomic `rename()`, restart. If the new app doesn't stay up ~8 s → **auto-revert** to the backup; power-loss mid-swap reverts on next boot. No A/B partitions in v1. |
| 5 | Signing scheme + where the public key lives | **v1 = SHA-256 only**, with the SHA-256 delivered over the **trusted BLE link** (BLE is the authenticated channel; integrity guaranteed). **ed25519 signing is deferred** to a later rev (code seam is in place). So for v1 you do **not** need a signing pipeline or a burned-in key yet. |
| 6 | Manifest + image hosting URL; key custody | **No manifest for v1.** We use **direct `OTA_INSTALL`** — you send `url`+`ver`+`sha256` straight over BLE. **You host the image** (see §2). No `manifest.json`, no `OTA_CHECK` on the board in v1. |
| 7 | Post-flash re-advertise on `0xAF00` so app reconnects | **v1 does not reboot.** Only the display app process restarts (~1–2 s); the **BLE GATT server is a separate process and keeps advertising `0xAF00` throughout**, so the link should not drop. Expect at most a brief telemetry pause during the app restart — your existing frame-watchdog/reconnect path covers it, but a full rescan usually won't even be triggered. |
| 8 | Status channel: in-band telemetry vs dedicated `OtaStatus` msg | **In-band telemetry.** `otaState` (enum 0–8, your §5b) + `otaProgress` (0–100) appended to `VotolTelemetry` at **`@18` / `@19`** (see collision note). No new characteristic. ✅ **NOW LIVE** (`otaProgress` emits 0 until we add the numeric producer — see §3). |

> ### ⚠️ Ordinal correction — OTA fields go at `@17/@18/@19`, NOT `@15/@16/@17`
> Your doc §3 proposed `fwVersion@15` / `otaState@16` / `otaProgress@17`, assuming
> `@15+` was free. **It isn't.** Our `votol_telemetry.capnp` already appended the
> **trip-meter fields at `@15` (`tripAMeters`) and `@16` (`tripBMeters`)** on
> 2026-07-07, and `@0..@16` are **frozen**. Reusing `@15/@16` would collide with
> the trip meters and corrupt the wire for existing readers. So the OTA fields
> take the next free ordinals:
> ```capnp
> fwVersion   @17 :UInt32;   # packed semver (major<<16)|(minor<<8)|patch
> otaState    @18 :UInt8;    # enum 0..8 (your §5b)
> otaProgress @19 :UInt8;    # 0..100
> ```
> These append cleanly (`LEN` grows again); your app already honors `LEN` and
> ignores trailing fields, so old builds are unaffected. **Read them at `@17..@19`.**

---

## 2. The v1 contract, concretely (what each side does)

**Trigger:** the user taps **UPDATE FW** on the board's SYSTEM menu (already live).
The board raises an internal request and then waits for your `OTA_INSTALL`.

**Your (app) responsibilities in v1:**
1. **Get the board onto the network** — keep using the existing
   `WIFI_SSID` / `WIFI_PSK` / `WIFI_JOIN` control commands
   (`BOARD-WIFI-STA-INTEGRATION.md`) so the board can join your hotspot. The OTA
   daemon calls `wifi_sta ensure` itself, but it needs valid saved creds.
2. **Host the new `lvgl_evdash` binary** on a plain-HTTP URL reachable on the
   hotspot (e.g. a small HTTP server on the phone at
   `http://<phone-hotspot-ip>:<port>/lvgl_evdash`). HTTPS is also accepted by the
   board if you prefer, but a local hotspot IP has no public CA, so **plain HTTP +
   the BLE-delivered SHA-256 is the intended v1 path.**
3. **Send `OTA_INSTALL`** (your §5a) once the board is joined:
   - `category = 32`, `title = "OTA_INSTALL"`
   - body (newline-separated `key=value`):
     ```
     url=http://<phone-hotspot-ip>:<port>/lvgl_evdash
     ver=1.2.3
     sha256=<64-hex lowercase of the binary>
     ```
   - `ver` = **dotted semver string** (not an integer). The board only proceeds if
     `ver` > its running `fwVersion`.
4. **Render progress** from `otaState`/`otaProgress` on the telemetry stream
   (once §3 lands), and confirm SUCCESS by reading the new `fwVersion` after the
   app restarts.

**Board responsibilities (implemented):**
read descriptor → validate → `wifi_sta ensure` → download via `map-fetch` →
verify SHA-256 (deletes file on mismatch) → newer-semver gate → back up + atomic
swap + auto-revert fail-safe → `wifi_sta restore` → publish `otaState`.

**`otaState` enum (matches your §5b):**
`0 IDLE · 1 CHECKING · 2 AVAILABLE · 3 DOWNLOADING · 4 VERIFYING · 5 APPLYING · 6 REBOOTING · 7 SUCCESS · 8 FAILED`.
(v1 uses APPLYING/SUCCESS/FAILED; no true REBOOTING since we don't reboot.)

---

## 3. Telemetry version + progress — ✅ NOW LIVE ON THE WIRE

`fwVersion@17`, `otaState@18`, `otaProgress@19` are **appended to `VotolTelemetry`
and DEPLOYED to the board** — you can read them today:

- **`fwVersion@17` : UInt32** — packed semver `(major<<16)|(minor<<8)|patch`.
  Currently **`65536` = `0x010000` = V1.0.0** (the real running board version —
  replace your hardcoded `v1.0.0` with the decode of this field).
- **`otaState@18` : UInt8** — enum 0–8 (§5b); `0 IDLE` when no OTA active.
- **`otaProgress@19` : UInt8** — 0–100. *Emitted, currently always 0* — the v1
  daemon reports lifecycle **state** but not a numeric byte-progress yet (see
  open item #1); wire the progress bar off `otaState` for now, `otaProgress`
  goes live when we add the producer.

**Frame change (append-only, zero migration for you):** the telemetry payload
grew from **LEN `0x38` (56 B)** to **LEN `0x40` (64 B)** — data section 40→48 B
(6 words). Fields `@0..@16` are byte-unchanged; `@17..@19` are the new trailing
bytes. Old decoders that honor `LEN` ignore them; decode at `@17..@19` to read
them. **Verified on-wire:** the live 64-byte frame carries bytes 56–59 =
`00 00 01 00` little-endian = `65536` = V1.0.0.

Source of truth on the board: `fwVersion` ← `/etc/evdash_version`; `otaState` ←
the OTA daemon (`/var/run/ota.otastate`).

---

## 4. Scope, limits & what is NOT in v1

- **Updates the display app binary only** (~1 MB), not kernel/rootfs/assets. The
  "firmware version" you show is the app version.
- **No** `manifest.json`, **no** `OTA_CHECK`/`OTA_CANCEL` on the board, **no**
  ed25519, **no** A/B partitions, **no** silent/background auto-update — all v1
  non-goals (some already yours).
- **Board-side gates (safety):** the daemon stays fully dormant until we create
  `/etc/ota.enabled=1` on the unit, and it refuses any `ver` ≤ the running one.
  So an `OTA_INSTALL` today is a safe no-op until we enable it.
- **Storage:** we're clearing space on the board rootfs before enabling (an OTA
  needs the current + backup + new binary transiently on one filesystem). This is
  a board-side task, not a blocker for your side.

---

## 5. What we need back from you

1. Confirm you'll **host the binary over HTTP on the hotspot** and send
   `OTA_INSTALL{url,ver,sha256}` as in §2.
2. Confirm the **`ver` = dotted-semver** convention on your encoder (matches your
   §5a table).
3. Ack that **v1 status/version arrive via telemetry `@17..@19`** (note the
   ordinal correction above; we'll signal when live) — so you wire `OtaController`
   to the telemetry flow rather than a dedicated characteristic.

Once you ack 1–3, v1 is frozen and both sides are unblocked. ed25519 signing +
`manifest.json`/`OTA_CHECK` remain the natural v2.
