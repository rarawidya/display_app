# EVdisplay — OTA Firmware Update Integration (proposal v0, to align with firmware team)

**Status:** DRAFT / not implemented. The app ships an OTA *UX shell*
([`OtaUpdateSheet.kt`](../app/src/main/java/com/example/displayapp/presentation/ui/home/OtaUpdateSheet.kt))
whose check is a placeholder (`delay(1_800)` → always "up to date"). This
document proposes the wire contract + division of labour so we can replace the
placeholder with a real flow. **Nothing here is final** — the ⭐ sections are
decisions we need from the firmware team before either side writes code.

Companion docs: transport + framing in [`capnpble.md`](capnpble.md); the board's
Wi-Fi STA join in [`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md);
control-command pattern (`category=32`) in `capnpble.md §5b`.

---

## ⭐ ACTION REQUIRED (firmware team) — decisions blocking implementation

In priority order. Answers turn this v0 into a v1 contract.

1. **What are we updating?** The **EVdisplay board** firmware (SG2002 app +
   assets), the **VOTOL motor controller** firmware (via the board's serial
   link), or both as independent targets? The rest of the design differs by
   answer. *This doc assumes the **board** is target #1 and treats the VOTOL
   controller as a separate, later target — please confirm.*
2. **Transport for the image bytes — network-pull or BLE-push?** Strong
   recommendation below is **board pulls over Wi-Fi/network, phone only
   orchestrates** (the SG2002 is Linux with Wi-Fi STA already). Confirm the
   board can reach an HTTPS URL when joined to the phone hotspot / any AP.
3. **How does the board report its running firmware version today?** There is
   **no version field on the BLE wire** right now — the app hardcodes
   `v1.0.0`. We need a real source (see §3). Preferred: append `fwVersion` to
   `VotolTelemetry` at `@15+`.
4. **Update mechanism on-device:** A/B partitions + rollback? Single partition
   with a recovery fallback? swupdate / RAUC / Mender / custom? This decides
   whether a failed flash can brick a unit.
5. **Image signing / verification:** who signs, what key, is the public key
   burned into the board? (We must not flash unsigned images.)
6. **Where do release images + the "latest version" manifest live?** A URL the
   board (or phone) can GET. Format proposed in §4.

---

## 1. Goals & non-goals

**Goals**
- Show the user the **real** running firmware version.
- Let the user **check** for a newer version and see release notes.
- Let the user **start** an update, see **live progress**, and get a clear
  success/failure result — without bricking the vehicle.
- Reuse the existing BLE transport (`0xAF07` downlink, framed capnp) and the
  existing `category=32` control-command mechanism wherever possible.

**Non-goals (v1)**
- Silent/background auto-update. v1 is user-initiated only.
- Delta/differential images. Full-image first; deltas later if size hurts.
- Updating the phone app itself (that's the Play Store's job).

---

## 2. Two architectures — recommendation

### Option A — **Board pulls over network, phone orchestrates** ✅ RECOMMENDED

The SG2002 runs Linux and already supports Wi-Fi STA (it joins the phone's
hotspot, see `BOARD-WIFI-STA-INTEGRATION.md`). So the board can download its own
image over HTTPS at full Wi-Fi speed and verify it locally. The phone's job
shrinks to: trigger a check, hand the board a URL, and render progress/status
the board reports back.

```
Phone                              Board (SG2002 + Wi-Fi)            Release server
  │  OTA_CHECK (category=32) ───────▶│                                     │
  │                                  │── GET /manifest.json ──────────────▶│
  │                                  │◀─ {latest, url, sha256, notes} ─────│
  │◀── OtaStatus{available, ver} ────│                                     │
  │  OTA_INSTALL{url,sha,ver} ──────▶│                                     │
  │                                  │── GET firmware.bin ────────────────▶│
  │◀── OtaStatus{downloading, 42%} ──│  (verify sig+sha, write, swap)      │
  │◀── OtaStatus{applying, reboot} ──│                                     │
  │◀── OtaStatus{success, newVer} ───│  (after reboot, via telemetry ver)  │
```

**Pros:** fast (Wi-Fi vs ~1–3 KB/s over BLE), robust (no multi-thousand-frame
BLE transfer to babysit), image verification stays on the board, minimal new
BLE surface. **Cons:** board needs network egress at update time (mitigated —
the phone can share its hotspot; that flow already exists).

### Option B — **Phone pushes the image over BLE** (fallback only)

Phone streams the firmware image to the board in framed chunks over a write
characteristic. Needed only if some units can't get network egress.

**Pros:** works with zero board network. **Cons:** slow and fragile — a 4 MB
image at ATT_MTU 247 is ~20k writes; needs windowing/acks, resume, and careful
watchdog interaction. Propose we **defer B** unless a no-network unit is a real
requirement.

> The message contract in §3–§5 is written for **Option A**. If we adopt B, we
> add a `RECOMMENDED`-vs-fallback note and a chunk/ack sub-protocol on a
> dedicated characteristic (sketch in §7).

---

## 3. Firmware version reporting (prerequisite for everything)

The app must show the true running version and detect when a reboot landed the
new one. **Preferred:** append to the frozen-but-extensible `VotolTelemetry`
(new fields append at `@15+`, `LEN` grows — see `capnpble.md §3`):

```capnp
struct VotolTelemetry {
  # … @0..@14 FROZEN …
  fwVersion  @15 :UInt32;   # packed semver: (major<<16)|(minor<<8)|patch  e.g. 0x010203 = 1.2.3
  # optional, only if we do in-band OTA status (§5, Option A):
  otaState    @16 :UInt8;   # enum below; 0 = idle
  otaProgress @17 :UInt8;   # 0..100, meaningful while downloading/applying
}
```

*Why append to telemetry rather than a new characteristic:* it's already
streaming ~10 Hz, the app already honors `LEN` (never assumes 48 bytes), and old
app builds ignore trailing fields — zero migration risk. **If** the firmware
team prefers a separate read characteristic for version, that's fine too; the
app can `readCharacteristic` on connect instead. Please pick one in ⭐#3.

Packed-semver keeps it a single `UInt32` and sorts numerically for
"is-newer" comparison.

---

## 4. Release manifest & image (server side)

A static JSON the board (Option A) GETs. Proposed shape:

```json
{
  "target": "evdisplay-board",
  "latest": "1.2.3",
  "minInstallable": "1.0.0",
  "url": "https://ota.example.com/evdisplay/1.2.3/firmware.bin",
  "size": 4194304,
  "sha256": "e3b0c44298fc1c149afbf4c8996fb924...",
  "signature": "base64(ed25519 over sha256)",
  "mandatory": false,
  "notes": "Regen threshold fix; faultCode bitfield populated."
}
```

- **`target`** disambiguates board vs VOTOL controller once we have both.
- **`sha256` + `signature`** — the board verifies both before writing. App never
  needs the key.
- **`notes`** is shown in the OTA sheet (Markdown-lite or plain text — confirm).
- Hosting: any HTTPS/CDN or object store. URL + signing key are release-team
  secrets (mirror the `local.properties` pattern — never in git).

---

## 5. BLE message contract (Option A)

### 5a. Downlink — OTA control commands (`category = 32`, on `0xAF07`)

Reuse the existing control-command mechanism (`capnpble.md §5b`): a
`PhoneNotification` with `category = 32`, the command name in `title`, and
`key=value` params in the body. **No new schema** — the board already parses
these; it just needs to recognise new titles. Unknown `category=32` titles are
already logged-and-ignored (safe no-op), so this is backward compatible.

| `title` | body params | meaning |
|---|---|---|
| `OTA_CHECK` | `manifest=<url>` (optional; else board default) | fetch the manifest, reply with availability |
| `OTA_INSTALL` | `url=…` `ver=1.2.3` `sha256=…` | download, verify, apply that image |
| `OTA_CANCEL` | — | abort an in-progress download (pre-apply only) |

> Body already carries `key=value` lines for the banner UI (`capnpble.md`),
> so params ride the existing `body` field. If a URL + sha exceeds the ~240 B
> frame budget, we either shorten (host by content-hash path) or switch this
> pair to Option A's board-default-manifest (board holds the base URL; phone
> sends only `ver`).

### 5b. Uplink — OTA status

Two equivalent options; pick per ⭐#2/#3:

- **In-band (preferred):** the `otaState`/`otaProgress` fields on
  `VotolTelemetry` (§3). The app already consumes telemetry ~10 Hz, so progress
  is "free" and self-healing (no acks). State enum:

  | value | state | notes |
  |---|---|---|
  | 0 | `IDLE` | no OTA activity |
  | 1 | `CHECKING` | manifest fetch in progress |
  | 2 | `AVAILABLE` | newer version found (app reads `fwVersion` on manifest vs `latest`) |
  | 3 | `DOWNLOADING` | `otaProgress` = 0..100 |
  | 4 | `VERIFYING` | sha/signature check |
  | 5 | `APPLYING` | writing / swapping partition; do not power off |
  | 6 | `REBOOTING` | board will drop the BLE link briefly |
  | 7 | `SUCCESS` | confirm by reading new `fwVersion` after reconnect |
  | 8 | `FAILED` | pair with a fault reason (below) |

  For `AVAILABLE`/`FAILED` detail (version string, error text) the board can
  emit a one-shot `category=6`-style banner or a small dedicated message — TBD
  with ⭐#2.

- **Dedicated message/characteristic:** a small `OtaStatus` capnp struct on its
  own notify characteristic (e.g. `0xAF0A`), mirroring how navigation got
  `0xAF06`. Cleaner separation, but new GATT surface. Only worth it if status
  needs rich text the telemetry fields can't carry.

---

## 6. Safety & UX rules (both sides must enforce)

- **Never update while riding.** App gates the Install button on `speed == 0`
  **and** ideally `flags` park bit set; board should also refuse if moving.
- **Charge/health preconditions.** Recommend battery ≥ ~40% (or on charger)
  before `APPLYING`. Board is the final authority; app pre-checks for a good UX.
- **Power-loss safety.** `APPLYING` must be power-fail safe (A/B swap or
  journaled write) so a yanked battery can't brick — this is ⭐#4.
- **Signed images only.** Board rejects bad `sha256`/`signature` and reports
  `FAILED` with reason. App never sees the key.
- **Watchdog interaction.** During `APPLYING`/`REBOOTING` the telemetry stream
  will pause and the BLE link may drop. The app's frame watchdog force-closes
  on ~3 s silence and reconnects-by-rescan (`BleDataSource`). The board should
  expect the central to disappear and re-advertise on the same `0xAF00` service
  so the app's normal reconnect path re-establishes the session and reads the
  new `fwVersion`. **Confirm the post-flash re-advertise behaviour.**
- **Idempotent / resumable.** If the link drops mid-download, `OTA_INSTALL`
  with the same `ver` should resume or restart cleanly, not corrupt.

---

## 7. Option B sketch (BLE-push) — only if we need no-network units

Not the recommendation; recorded so we don't have to re-derive it.

- New characteristic `0xAF0B` (Write-No-Rsp) for image chunks; `0xAF07`
  `category=32` `OTA_BEGIN{ver,size,sha256}` / `OTA_END` to bracket.
- Windowed chunks: `[seq:uint16][bytes…]`, board ACKs every N via an uplink
  status field; phone throttles to the ACK window; resume from last ACKed seq.
- Expect ~1–3 KB/s effective → a 4 MB image is ~20–60 min. This is why A wins.

---

## 8. Phone-side integration plan (our side)

Single seam already exists — the placeholder in `OtaUpdateSheet.kt`
(`delay(1_800)`), noted in-code as "the single seam to wire a real update
service into later." Plan once the contract lands:

1. **`fwVersion` → UI.** Replace the hardcoded `v1.0.0` (HomeScreen /
   `OtaUpdateEntry(currentFirmware=…)`) with the decoded telemetry field.
2. **`OtaController`** (new, `data/ota/`) — sends the `category=32` commands via
   the existing `PhoneNotificationSender` (`0xAF07`), and exposes a
   `StateFlow<OtaUiState>` derived from `otaState`/`otaProgress` on the
   telemetry stream. Provider-agnostic, testable, mirrors how
   `NavigationCoordinator` wraps the nav protocol.
3. **`OtaUpdateSheet`** consumes that state: Idle → Check → Available (notes +
   Install) → Downloading (%) → Applying → Success/Failed, with the safety gates
   from §6.
4. **Reconnect confirm.** After `REBOOTING`, wait for the normal reconnect, read
   `fwVersion`, and show SUCCESS only when it matches the target version.
5. **Tests.** Command encode is byte-verifiable against the capnp CLI like the
   other protocols (`NavigationFrameTest` pattern); `OtaController` state
   transitions unit-tested off a fake telemetry flow.

No new dependencies; no Room changes; reuses `0xAF07` + the frame codec.

---

## 9. Open items summary (copy into the shared tracker)

| # | Owner | Question |
|---|---|---|
| 1 | firmware | Target(s): board / VOTOL controller / both? |
| 2 | firmware | Transport: network-pull (Option A) confirmed? BLE-push needed? |
| 3 | firmware | Version source: append `fwVersion@15` to telemetry, or read characteristic? |
| 4 | firmware | On-device mechanism: A/B + rollback? swupdate/RAUC/custom? |
| 5 | firmware | Signing scheme + where the public key lives |
| 6 | release | Manifest + image hosting URL; signing key custody |
| 7 | firmware | Post-flash re-advertise on `0xAF00` so the app reconnects cleanly |
| 8 | both | Status channel: in-band telemetry fields vs dedicated `OtaStatus` msg |

Once 1–3 are answered we can freeze a v1 contract and both sides can start.
