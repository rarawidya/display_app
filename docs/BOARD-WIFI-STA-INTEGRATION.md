# EVdisplay — Board Internet via Phone Hotspot (Wi-Fi STA) Integration Spec (v1, PROPOSED)

**Date:** 2026-07-08 · **From:** Android app team · **To:** firmware (EVdisplay board)
**Decision (owner):** the board gets internet for **map-pack downloads** by joining the
**phone's Wi-Fi hotspot as a station (STA)**. The phone app delivers the hotspot
credentials to the board over the proven `0xAF07` control channel; the board runs
`wpa_supplicant`, joins, downloads, and returns to its normal mode.
**Status:** app side **IMPLEMENTED** (credentials UI + delivery, ships in the current
build); board side **NOT BUILT** — this doc is the contract.

**Why STA over the alternatives considered:**
- Bluetooth tethering (PAN): ~0.2 MB/s real-world and it shares the BT radio with the
  live BLE links (10 Hz telemetry + nav heartbeat) — a bulk download would starve them.
  A 2–6 GB tile pack (your East Java estimate) is hours over PAN vs minutes over Wi-Fi.
- Phone-pushes-pack-over-board-AP stays viable later, but the owner chose STA so the
  board can fetch directly (resumable downloads, no phone storage double-hop).

---

## 1. The firmware work (your side)

1. **Add `wpa_supplicant` to the image** — you already established the AIC8800 driver
   supports managed/STA mode (`iw phy`, MAPS-REALMAP-PROPOSAL Part A); only the
   userspace piece is missing.
2. **Mode management.** The board normally runs AP (`hostapd`, used later by the
   map-stream feature). Unless the AIC8800 does concurrent AP+STA reliably, treat
   **download mode** as exclusive: on `WIFI_JOIN` stop AP → STA join → download → on
   completion/failure/`WIFI_FORGET` return to AP. Surface state via §4 (or at minimum
   log it). Do not stay in STA while riding — the map-stream/AP path must win.
3. **Persist credentials** (survive reboot) so a later "update maps" can rejoin
   without the phone resending. Store like any other config; see §5 security notes.
4. **Downloader.** OpenSSL 3 is already in the image; busybox wget has no TLS — a small
   HTTPS fetcher (or adding curl) is needed. Resume support (`Range`) strongly
   recommended for multi-GB packs on a phone hotspot.
5. **Licensing guard:** map packs must be built from **OSM/Geofabrik data (ODbL, $0)**.
   **Never download or store MapTiler tiles on the board** — their terms prohibit it
   (your own decision record, MAPS-REALMAP-PROPOSAL). The app's MapTiler key stays in
   the app.

---

## 2. Credential delivery — control commands on `0xAF07` (existing channel)

Reuses the **exact** `PhoneNotification` control-command contract that
`ODO_RESET_TRIP` already proves end-to-end (capnpble.md §5b): `category = 32`,
`appName = "EVD"`, command name in `title`, argument in `body`. One acknowledged GATT
write per command, standard `[0xAA][LEN][capnp][CRC16-LE]` frame.

The app sends this sequence, in order, each Write-With-Response:

| # | `title` | `body` | Meaning |
|---|---|---|---|
| 1 | `WIFI_SSID` | hotspot SSID (≤ 32 UTF-8 bytes) | stage the network name |
| 2 | `WIFI_PSK` | WPA2 passphrase (8–63 chars) | stage the passphrase |
| 3 | `WIFI_JOIN` | empty | commit: bring up STA with the staged credentials |
| — | `WIFI_FORGET` | empty | wipe stored credentials; return to AP mode |

Rules:
- `WIFI_SSID`/`WIFI_PSK` only **stage** values; nothing changes until `WIFI_JOIN`.
  A `WIFI_JOIN` with no staged pair since boot = rejoin with persisted credentials.
- Both ends sanitize control characters to spaces (existing banner rule), so
  passphrases containing control characters are unsupported — WPA2 passphrases are
  printable ASCII by spec anyway. All other printable UTF-8 passes through; `body`
  cap 96 B is far above the 63-char WPA2 maximum.
- `id` is 0; `flags` 0; `timestampUnix` may be set — all ignored for commands.
- Commands are idempotent; the app may resend the whole sequence.

---

## 3. Concrete example (field spec)

```
1. category=32  appName="EVD"  title="WIFI_SSID"  body="Rara's Phone"
2. category=32  appName="EVD"  title="WIFI_PSK"   body="ride-safe-2026"
3. category=32  appName="EVD"  title="WIFI_JOIN"  body=""
```
Byte-level framing is identical to the verified `ODO_RESET_TRIP` frame — no new
encoder work on either side. Every accepted write already hex-dumps to
`/var/log/ble-gatt.log`, which is the verification hook (§6).

---

## 4. Status feedback — `WifiStatus` on the `0xAF05` uplink (proposed, v1.1)

v1 works blind (the rider sees join/download progress on the board's own screen).
For app-side progress UI, extend the control uplink (CALL-CONTROL-INTEGRATION.md)
with **`ctrlType = 0x02`**:

```capnp
struct WifiStatus {
  ipv4  @0 :UInt32;  # 0 until connected (network byte order LE-packed like all fields)
  seq   @1 :UInt16;  # rolling, +1 per status change
  state @2 :UInt8;   # 0 idle/AP · 1 joining · 2 connected · 3 join failed
                     # 4 downloading · 5 download done · 6 download failed
}
```
Same one-data-word shape as `CallControl` (ipv4 @0..3, seq @4..5, state @6) inside the
same `[0xAA][LEN][ctrlType‖capnp][CRC16-LE]` frame. Send on every state change. The
app already subscribes to `0xAF05` and ignores unknown `ctrlType`s, so shipping this
later needs **no app update to stay compatible** (and one small update to display it).

---

## 5. Security notes (read before implementing)

- The GATT link is **open and unencrypted** — the hotspot passphrase crosses the air
  in cleartext during the ~1 s credential push. Mitigations, in practice: BLE range
  is proximity-bounded, the push is a rare user-initiated action, and the app advises
  the user to use their hotspot's dedicated password (never a reused one). The real
  fix is BLE pairing/LE Secure Connections on this characteristic — worth bundling
  into any future firmware security pass; the command contract above doesn't change.
- Store the persisted passphrase root-readable only; don't echo it to
  `/var/log/ble-gatt.log` (mask the `WIFI_PSK` body in the hex-dump path — this is
  the ONE frame whose payload should not be logged verbatim).

---

## 6. Verification (no special tooling)

1. In the app: Settings → **Vehicle internet** → enter hotspot SSID + password →
   **Send Wi-Fi to display** (app must show "Sent to display").
2. Board: `tail -f /var/log/ble-gatt.log` → three DOWNLINK frames (`WIFI_SSID`,
   `WIFI_PSK` masked, `WIFI_JOIN`).
3. Enable the phone hotspot (the app's "Open hotspot settings" row deep-links there —
   Android does not allow apps to switch the hotspot on programmatically).
4. Board: `wpa_cli status` → `wpa_state=COMPLETED`; `ping 1.1.1.1` works; fetch a
   test file over HTTPS.
5. `WIFI_FORGET` from the app (long-press the send row) → creds wiped, AP restored,
   phone can rejoin the board's AP.

---

## 7. Out of scope for v1

- Concurrent AP+STA (confirm AIC8800 support before ever relying on it).
- WPA3/enterprise networks — phone hotspots are WPA2-PSK.
- The map-pack format/URL scheme itself (separate doc once the OSM pack pipeline is
  chosen); this spec only gets the board online.

---
*Owner: mobile + firmware · Companions: [`APP-NOTIFICATION-INTEGRATION.md`](APP-NOTIFICATION-INTEGRATION.md)
(command framing), [`CALL-CONTROL-INTEGRATION.md`](CALL-CONTROL-INTEGRATION.md) (uplink framing),
[`MAPS-REALMAP-PROPOSAL.md`](MAPS-REALMAP-PROPOSAL.md) (hardware audit + licensing).*
