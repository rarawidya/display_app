# How the Display Controller Connects to the Phone's Hotspot (As-Built)

**Date:** 2026-07-09 · **Audience:** app + firmware engineers · **Status:** app side
**IMPLEMENTED & shipping**; firmware (board) side **NOT BUILT** — see the contract in
[`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md).

This document is the **full, end-to-end, as-built** description of how the EVdisplay
board gets onto the internet through the rider's phone. It maps every step to the
actual code that ships today. [`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md)
is the firmware-facing *contract* (what the board must implement); this file is the
*mechanism* (how the phone app already does its half).

---

## 0. TL;DR

The board does **not** connect over Bluetooth for bulk data. Instead:

1. The phone runs a **Wi-Fi hotspot (soft-AP)**.
2. The board joins that hotspot as a **Wi-Fi station (STA)** — a normal Wi-Fi client —
   using `wpa_supplicant`, and downloads its map packs over ordinary HTTPS.
3. The **only** thing that crosses Bluetooth is the tiny **credential handoff**: the
   phone pushes the hotspot SSID + password to the board over the existing `0xAF07`
   control characteristic, as three framed control commands (`WIFI_SSID`, `WIFI_PSK`,
   `WIFI_JOIN`).

So Bluetooth carries the *credentials*; Wi-Fi carries the *data*. This keeps multi-GB
map downloads off the BLE radio (which is busy with 10 Hz telemetry + the nav stream).

```
   PHONE                                                   BOARD (EVdisplay)
   ┌───────────────────────────┐                          ┌────────────────────────┐
   │ Settings → Vehicle        │  1. credentials over BLE │ 0xAF07 control RX       │
   │ internet                  │  ────────────────────▶   │  WIFI_SSID / PSK / JOIN │
   │  • SSID + password (saved)│     (0xAF07, 3 writes)   │                         │
   │  • "Send Wi-Fi to display"│                          │  wpa_supplicant → STA   │
   │  • "Open hotspot settings"│                          │        │                │
   │                           │                          │        ▼                │
   │  ┌─────────────────────┐  │  2. board joins Wi-Fi    │  joins phone hotspot    │
   │  │ Phone soft-AP (Wi-Fi│◀─┼──────────────────────────┤  as a STA client        │
   │  │ hotspot) ON          │  │     as a normal client   │        │                │
   │  └─────────────────────┘  │                          │        ▼                │
   │      (phone's uplink:     │  3. HTTPS map-pack        │  downloads OSM map pack │
   │       LTE/5G/other Wi-Fi) │  ◀──── download over ─────│  over the hotspot       │
   └───────────────────────────┘        Wi-Fi/internet     └────────────────────────┘
```

---

## 1. Why Wi-Fi STA and not Bluetooth for the data

The decision (owner-chosen) is recorded in
[`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md) §"Why STA":

- **Bluetooth PAN/tethering** is ~0.2 MB/s and shares the radio with the live BLE
  links (10 Hz telemetry + nav heartbeat) — a bulk download would starve them. A
  2–6 GB regional tile pack is *hours* over PAN vs *minutes* over Wi-Fi.
- **Wi-Fi STA** lets the board fetch directly (resumable downloads, no phone-storage
  double-hop).

BLE therefore only ever carries the ~1 s credential push.

---

## 2. The user-facing flow (Settings → Vehicle internet)

All UI lives in the **"Vehicle internet"** section of the Settings screen:
[`SettingsScreen.kt` → `VehicleInternetSection`](../app/src/main/java/com/example/displayapp/presentation/ui/settings/SettingsScreen.kt) (line 528+).

| Row | What it does | Backing call |
|---|---|---|
| **Set / edit hotspot credentials** | Opens `HotspotCredentialsDialog` to enter the SSID + password | `SettingsViewModel.setHotspotCredentials()` |
| **Send Wi-Fi to display** | Pushes the saved credentials to the board over BLE. Tint is green when configured + connected, amber otherwise | `SettingsViewModel.sendWifiCredentialsToBoard()` |
| **Open hotspot settings** | Deep-links to Android's tethering settings so the user turns the hotspot **on** | `Intent` to `TetherSettings` (see below) |
| **Clear Wi-Fi from display** | Tells the board to wipe stored credentials + return to AP mode (only shown when configured) | `SettingsViewModel.forgetBoardWifi()` |

**Important Android constraint:** an app **cannot** programmatically switch the phone's
hotspot on — that is reserved for system apps. So the app *stores* + *sends* the
credentials and then **deep-links** the user to the system hotspot toggle. The comment
at `SettingsScreen.kt:223` documents this. The rider taps "Open hotspot settings" and
flips the hotspot on manually.

### Typical rider sequence
1. Settings → **Vehicle internet** → set hotspot **name (SSID)** + **password** → Save.
2. Make sure the display is BLE-connected (the "Send Wi-Fi to display" row shows why if
   not).
3. Tap **Send Wi-Fi to display** → app shows "Wi-Fi credentials sent — turn the hotspot
   on".
4. Tap **Open hotspot settings** → turn the phone hotspot **on**.
5. The board joins, downloads maps, and returns to its normal AP mode.

---

## 3. Where the credentials live (persistence)

Entered credentials are persisted in DataStore so the user doesn't re-type them:

- Storage keys `KEY_HOTSPOT_SSID` / `KEY_HOTSPOT_PSK` in
  [`AppPreferences.kt`](../app/src/main/java/com/example/displayapp/data/preferences/AppPreferences.kt)
  (lines 111–112; read at 50–51; write via `setHotspotCredentials()` at line 90).
- Surfaced on the domain model as `AppSettings.hotspotSsid` / `hotspotPassword`
  ([`AppSettings.kt`](../app/src/main/java/com/example/displayapp/domain/model/AppSettings.kt) lines 106–107).
- Written through `AppPreferencesRepository.setHotspotCredentials()`.

> **Security note:** the passphrase is stored in the app's private DataStore as
> plaintext (app-sandboxed). See §7.

---

## 4. Credential delivery over BLE — the exact frames

This is the heart of "how the controller connects to the hotspot from the app". The app
sends **three** control commands over the `0xAF07` control characteristic, each a
separate BLE write, in order. All three must succeed for the board to commit.

### 4.1 The command builder
[`BoardWifiCommands.kt`](../app/src/main/java/com/example/displayapp/data/notification/BoardWifiCommands.kt):

```kotlin
BoardWifiCommands.joinSequence(ssid, passphrase) = [
    command("WIFI_SSID", ssid),         // stage the network name
    command("WIFI_PSK",  passphrase),   // stage the passphrase
    command("WIFI_JOIN"),               // commit: bring up STA with staged creds
]
BoardWifiCommands.forget() = command("WIFI_FORGET")   // wipe + return to AP
```

Each `command(...)` is a `PhoneNotification` with the **frozen control contract**
(the same one `ODO_RESET_TRIP` proves end-to-end, [`capnpble.md`](capnpble.md) §5b):

| Field | Value |
|---|---|
| `category` | `32` (`CATEGORY_CONTROL`) |
| `appName` | `"EVD"` |
| `title` | the command name (`WIFI_SSID` / `WIFI_PSK` / `WIFI_JOIN` / `WIFI_FORGET`) |
| `body` | the argument (SSID or passphrase; empty for JOIN/FORGET) |
| `id`, `flags` | `0` (ignored for commands) |

**Staging semantics:** `WIFI_SSID` and `WIFI_PSK` only *stage* values on the board;
nothing changes until `WIFI_JOIN` commits. A bare `WIFI_JOIN` (no fresh pair since boot)
means "rejoin with persisted credentials". The commands are idempotent — the app may
resend the whole sequence safely.

### 4.2 The send path (ViewModel → radio)
[`SettingsViewModel.sendWifiCredentialsToBoard()`](../app/src/main/java/com/example/displayapp/presentation/viewmodel/SettingsViewModel.kt) (line 192):

```kotlin
val ok = BoardWifiCommands.joinSequence(ssid, password)
    .all { notificationSender.send(it) }   // 3 reliable writes; all must return true
```

Each `PhoneNotification` flows through the shared, unit-tested encode+write pipeline:

```
PhoneNotification
  → PhoneNotificationSchema.encode(...)          // Cap'n Proto payload
  → FrameEncoder.encode(...)                     // [0xAA][LEN][payload][CRC16-CCITT-LE]
  → BluetoothDataSource.writeCommand(frame)      // → BleDataSource → BleGattClient
  → GATT write to characteristic 0xAF07 (RX_CHAR_UUID)
```

- Sender: [`PhoneNotificationSender.send()`](../app/src/main/java/com/example/displayapp/data/notification/PhoneNotificationSender.kt) — logs `writing 0xAF07 frame len=…` and records the push/drop in `DiagnosticsRepository`.
- Characteristic: `RX_CHAR_UUID = uuid16("AF07")` in [`BleConstants.kt`](../app/src/main/java/com/example/displayapp/data/bluetooth/ble/BleConstants.kt) line 28.

The wire framing is **byte-identical to the verified `ODO_RESET_TRIP` frame** — no new
encoder work on either side. On failure (board not connected, or on the simulator),
`writeCommand` returns `false` and the app shows "Couldn't reach the display — is it
connected?".

### 4.3 Concrete field example
```
1. category=32  appName="EVD"  title="WIFI_SSID"  body="Rara's Phone"
2. category=32  appName="EVD"  title="WIFI_PSK"   body="ride-safe-2026"
3. category=32  appName="EVD"  title="WIFI_JOIN"  body=""
```

---

## 5. What the board must do (firmware side — the contract)

This half is **not built** in the display firmware yet; it is specified in
[`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md) §1. Summary:

1. Add **`wpa_supplicant`** to the image (the AIC8800 driver already supports
   managed/STA mode; only the userspace piece is missing).
2. **Mode management** — the board normally runs AP (`hostapd`). Treat *download mode*
   as exclusive unless AIC8800 concurrent AP+STA is proven: on `WIFI_JOIN` → stop AP →
   STA join → download → on completion/failure/`WIFI_FORGET` → return to AP. Do not stay
   in STA while riding.
3. **Persist credentials** across reboot so "update maps" can rejoin without a resend.
4. **HTTPS downloader** with `Range`/resume support (OpenSSL 3 is already in the image;
   busybox `wget` has no TLS).
5. **Licensing guard:** map packs must be built from **OSM/Geofabrik data (ODbL, $0)** —
   never download/store MapTiler tiles on the board.

**Optional status feedback (v1.1, proposed):** the board can report join/download
progress back to the app via a `WifiStatus` struct on the `0xAF05` control uplink
(`ctrlType = 0x02`). The app already ignores unknown `ctrlType`s, so shipping this later
needs no breaking app change. See §4 of the contract doc.

---

## 6. Phone-side signal monitors (already implemented)

The app has two monitors that observe Wi-Fi state (used for status/UI hints; they do
**not** control the board):

- [`HotspotStateMonitor.kt`](../app/src/main/java/com/example/displayapp/data/system/HotspotStateMonitor.kt)
  — emits whether the phone's **soft-AP hotspot** is on. Android has no public hotspot-
  state API, so it stacks three signals: the `WIFI_AP_STATE_CHANGED` broadcast, an
  initial `getWifiApState()` reflection call, and an interface-name heuristic
  (`swlan0`/`ap0`/`softap0`/`wlan1`).
- [`WifiStateMonitor.kt`](../app/src/main/java/com/example/displayapp/data/system/WifiStateMonitor.kt)
  — emits whether the phone itself is connected to a Wi-Fi network
  (`ConnectivityManager` + `TRANSPORT_WIFI`).

---

## 7. Security

- The GATT link is **open and unencrypted** — the hotspot passphrase crosses the air in
  cleartext during the ~1 s push. Practical mitigations: BLE range is proximity-bounded,
  the push is a rare user-initiated action, and the UI advises using the hotspot's own
  dedicated password. The real fix is BLE pairing / LE Secure Connections on this
  characteristic (a future firmware security pass — the command contract doesn't change).
- The board must store the persisted passphrase root-readable only and **must not**
  echo the `WIFI_PSK` body into `/var/log/ble-gatt.log` (mask that one frame).
- On the phone, credentials sit in the app-private DataStore (plaintext, sandboxed).

---

## 8. Verification checklist

1. App: Settings → **Vehicle internet** → enter SSID + password → **Send Wi-Fi to
   display** → expect toast "Wi-Fi credentials sent — turn the hotspot on".
2. Board: `tail -f /var/log/ble-gatt.log` → three DOWNLINK frames (`WIFI_SSID`,
   `WIFI_PSK` **masked**, `WIFI_JOIN`).
3. App: **Open hotspot settings** → turn the phone hotspot on.
4. Board: `wpa_cli status` → `wpa_state=COMPLETED`; `ping 1.1.1.1` works; fetch a test
   file over HTTPS.
5. App: long-press / **Clear Wi-Fi from display** → board wipes creds, restores AP, phone
   can rejoin the board's AP.

App-side unit coverage: [`BoardWifiCommandsTest.kt`](../app/src/test/java/com/example/displayapp/data/notification/BoardWifiCommandsTest.kt).

---

## 9. Relationship to the map-route-over-Bluetooth feature

These are **two independent channels** — don't conflate them:

| Concern | Channel | What travels | Doc |
|---|---|---|---|
| **Map-pack download** (bulk tiles) | **Wi-Fi STA** (this doc); credentials over `0xAF07` | GBs of OSM map data | this file + [`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md) |
| **Turn-by-turn route + guidance** (live nav) | **BLE `0xAF06`** | route polyline + per-maneuver instructions | [`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md) + §9.1 below |

### 9.1 Map route → controller over Bluetooth (already implemented)

The route the rider picks on the phone map **is already sent to the controller over
BLE**, on its own `0xAF06` navigation characteristic (separate from telemetry `0xAF08`
and control `0xAF07`). This path is **fully implemented and byte-verified** end to end:

```
User picks destination on map
  → MapsViewModel.confirmDestination()
  → NavigationCoordinator.startPending()                 // preview → confirm → navigate
  → RouteNavigator.startNavigation(dest, name, plan)
       ├─ sendRoute():   RouteSummary (0x01)  + RouteChunk[] (0x03)   ← the route polyline
       └─ stream:        NavInstruction (0x02) at ≤1 Hz + heartbeat   ← turn-by-turn
  → BluetoothDataSource.writeNav(frame, reliable)
  → BleDataSource → BleGattClient.writeCommand(frame, NAV_CHAR_UUID=0xAF06)
```

- **Route geometry** is downsampled (≤200 pts, 40 pts/chunk), delta-encoded, and sent as
  `RouteChunk` frames — [`RouteNavigator.sendRoute()`/`routeChunks()`](../app/src/main/java/com/example/displayapp/data/navigation/RouteNavigator.kt) (lines 202, 209).
- **Turn-by-turn** `NavInstruction` frames stream at ≤1 Hz with a heartbeat so the
  board's liveness window doesn't lapse; the session re-seeds the route on BLE reconnect.
- **Encoders** are hand-built and byte-exact vs the `capnp` CLI —
  [`NavigationSchema.kt`](../app/src/main/java/com/example/displayapp/data/protocol/NavigationSchema.kt),
  verified in [`NavigationFrameTest.kt`](../app/src/test/java/com/example/displayapp/data/protocol/NavigationFrameTest.kt).
- **Schema**: [`navigation.capnp`](../app/schema/navigation.capnp).

**The only thing gating real-hardware delivery** is that the firmware must expose the
`0xAF06` characteristic; on firmware without it, `writeNav` returns `false` and no-ops
harmlessly (older firmware simply won't receive nav frames). See
[`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md) for the full firmware contract,
send policy, and framing.

---

*Companions:* [`BOARD-WIFI-STA-INTEGRATION.md`](BOARD-WIFI-STA-INTEGRATION.md) (Wi-Fi
firmware contract) · [`NAVIGATION-INTEGRATION.md`](NAVIGATION-INTEGRATION.md) (route/nav
over BLE) · [`capnpble.md`](capnpble.md) (BLE framing + control-command contract) ·
[`CALL-CONTROL-INTEGRATION.md`](CALL-CONTROL-INTEGRATION.md) (`0xAF05` uplink framing).
