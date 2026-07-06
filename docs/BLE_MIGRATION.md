# BLE Migration — Architecture Review

**Status:** review only — no code changed.
**Goal:** migrate the communication layer from Bluetooth Classic (SPP/RFCOMM) to
Bluetooth Low Energy (BLE/GATT) while keeping UI, ViewModels, Repository, business
logic, and protocol parsing **unchanged**.

---

## 1. Headline finding: the architecture is already built for this

The transport is isolated behind one interface, `BluetoothDataSource`:

```kotlin
interface BluetoothDataSource {
    val incomingData: SharedFlow<ByteArray>          // ← raw bytes, transport-agnostic
    val connectionState: StateFlow<ConnectionState>
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>>
    fun startDiscovery(); fun stopDiscovery()
    suspend fun connect(address: String); fun disconnect(); fun close()
}
```

Everything above it consumes **`SharedFlow<ByteArray>`** and never knows whether
those bytes came from an RFCOMM socket or a GATT notification. That is the whole
game: BLE delivers bytes → we emit them into `incomingData` → the existing
`FrameDecoder` reassembles frames exactly as today. **The migration lives almost
entirely below this interface.**

Two facts make this easier than a typical BLE migration:

- **The app is receive-only.** `SppSocketClient.write()` exists but has **zero
  callers** — there is no command/write path to port. BLE needs only a notify
  subscription, not a writable RX characteristic (until commands are added later).
- **Framing is transport-neutral.** `[0xAA][LEN][payload][CRC16-LE]` and
  `FrameDecoder` already buffer partial frames across arbitrary chunk boundaries —
  which is exactly what BLE's MTU fragmentation produces. No parser changes.

---

## 2. Component change map

| Layer | Component | Verdict |
|---|---|---|
| **Protocol** | `FrameDecoder`, `FrameEncoder`, `Crc16`, `TelemetryMapper`, `TelemetrySchema`, `TelemetryConstants`, `TelemetryDerivations` | ✅ **Unchanged** — byte-level, transport-agnostic |
| **Domain** | `VehicleData`, `ConnectionState`, repository interfaces | ✅ **Unchanged** |
| **Repository** | `VehicleRepositoryImpl` | ✅ **Unchanged** — binds to the `SwitchableDataSource` facade; still reads `SharedFlow<ByteArray>` |
| **Facade** | `SwitchableDataSource` | ✅ **Unchanged** — becomes the swap point (simulator ↔ BLE instead of ↔ SPP) |
| **ViewModels** | `DashboardViewModel`, `BluetoothViewModel`, Charts/Logs/etc. | ✅ **Unchanged** |
| **UI** | Home, Drive, `BluetoothQuickSheet`, all screens | ✅ **Unchanged** |
| **Service** | `TelemetryService` | ✅ **Unchanged** — still connects by MAC address, `foregroundServiceType=connectedDevice` |
| **Simulator** | `SimulatedDataSource` | ✅ **Unchanged** — emits framed bytes; independent of transport |
| **Transport (data plane)** | `SppDataSource`, `SppSocketClient`, `ReconnectPolicy`, frame watchdog | 🔁 **Replace** with `BleDataSource` + `BleGattClient` (reuse `ReconnectPolicy` + watchdog as-is) |
| **Transport (discovery)** | `BluetoothScanner` (Classic `ACTION_FOUND`) | 🔁 **Replace** with `BleScanner` (`BluetoothLeScanner` + `ScanFilter`) |
| **Adapter/bond plane** | `AndroidBluetoothController` (bonded list, Classic discovery) | 🔁 **Replace/adapt** with `BleController` implementing the **same `BluetoothController` interface** → the sheet UI stays untouched |
| **DI** | `AppContainer.createDelegate()` + `bluetoothController` | ✏️ **2-line swap** |
| **Manifest** | permissions + `uses-feature` | ✏️ **Small edit** |

**Net:** ~4 new files (BLE transport, GATT client, scanner, controller), ~2 edited
files (`AppContainer`, `AndroidManifest`), **0 changes** to UI / ViewModels /
Repository / protocol. The "keep everything else unchanged" goal is fully
achievable.

---

## 3. The one hard prerequisite (this is a two-sided change)

`capnp.md §1` is explicit: the board is a **Bluetooth Classic SPP RFCOMM server**
today, chosen deliberately ("*No GATT MTU juggling, no notify/indicate
bookkeeping*"). **BLE is not a phone-only migration** — the **firmware must also
become a GATT peripheral** advertising a service with a notify characteristic. If
that does not exist yet, the Android work has nothing to talk to.

**Before any BLE code is written, obtain this GATT contract from the
firmware/hardware team:**

1. **Service UUID** the board advertises.
2. **TX characteristic UUID** (board→phone telemetry) and whether it is **Notify**
   or **Indicate** (+ its CCCD `0x2902`).
3. **Advertised device name / service UUID** for scan-filtering (today it is
   `EVdashboard`).
4. **Address type** — public/static vs. random/resolvable. *(Critical: random
   addresses rotate, which breaks reconnect-by-MAC and the saved-device model —
   we would reconnect by service-UUID scan instead.)*
5. **Bonding/encryption** — "just works" unencrypted, or bonded/encrypted
   characteristics?
6. **MTU support** (default 23 → 20-byte payload; a ~40-byte frame becomes 2–3
   notifications).
7. Is the `[0xAA][LEN][CRC16]` framing **preserved inside GATT notifications**, or
   does each notification carry exactly one frame? (Either works with
   `FrameDecoder`; affects whether CRC/LEN become redundant.)

---

## 4. BLE-specific design decisions & gotchas

- **GATT operation serialization** — Android's stack allows **one** outstanding
  GATT op at a time. `discoverServices → requestMtu → writeDescriptor(CCCD)` must
  be **chained through callbacks** (or a small op-queue), or they silently fail.
  This is the #1 source of flaky BLE code; build a tiny serialized `BleGattClient`
  for it.
- **MTU** — request 247 right after connect (`onMtuChanged`) so one notification ≈
  one frame; otherwise `FrameDecoder` reassembles the fragments anyway (safe, just
  chattier).
- **Status 133 / transient connect failures** — reconnect via the existing
  `ReconnectPolicy`. Use `connectGatt(..., TRANSPORT_LE)`, `autoConnect=false` for
  a fast first connect.
- **Scanning cost** — `ScanFilter` on the service UUID, `SCAN_MODE_LOW_LATENCY`
  only while the sheet is open, stop on connect.
- **Watchdog** — keep the staleness watchdog; BLE links stall silently too.
- **Permissions/manifest** — already present: `BLUETOOTH_SCAN` (neverForLocation —
  still valid, we connect to a known device), `BLUETOOTH_CONNECT`, and
  `ACCESS_FINE_LOCATION (maxSdk 30)` for pre-Android-12 BLE scans. **Add**
  `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`.
- **"Paired" concept** — BLE devices often are not bonded; `BleController.pairedDevices`
  should come from BLE scan results + the saved-device pref, not `getBondedDevices()`.
  RSSI comes for free from `ScanResult` (cleaner than Classic).

---

## 5. Phased plan & status

- **Phase 0 — Prerequisites** ✅ **DONE.** Firmware confirmed the controller is a BLE
  GATT peripheral. GATT profile discovered on-device (Developer → BLE GATT probe):
  service **`0xAF00`**, telemetry-notify **`0xAF08`** (+CCCD), command-write
  **`0xAF07`**, MTU 256, no bonding, **resolvable-private (rotating) address**.
  Values captured in `docs/BLE_FIRMWARE_REQUIREMENTS.md` (Appendix A) — pending the
  firmware team's formal sign-off, but sufficient to build against.
- **Phase 1 — BLE transport behind the existing interface** ✅ **DONE (code-complete,
  pending on-hardware validation).** Implemented:
  - `ble/BleConstants.kt` — centralized UUIDs + MTU.
  - `ble/BleGattClient.kt` — serialized GATT handshake (connect → MTU → discover →
    CCCD enable → notify→bytes).
  - `ble/BleServiceScanner.kt` — find device by **service UUID** (handles the
    rotating address; reconnect-by-scan).
  - `ble/BleDataSource.kt` — `BluetoothDataSource` impl with reconnect (reuses
    `ReconnectPolicy`), staleness watchdog, and adapter-off → DISCONNECTED.
  - `AppContainer` — `useBleTransport` flag + `setBleTransport()`; `createDelegate()`
    selects BLE; swapped in via the existing `SwitchableDataSource`.
  - `AndroidManifest.xml` — added `uses-feature bluetooth_le`.
  - Developer → **"BLE transport (experimental)"** harness (toggle + connect/disconnect
    + live decoded readout) to validate end-to-end.

  **UI / ViewModels / Repository / protocol: untouched.** SPP remains the default.
- **Phase 2 — `BleController`** ✅ **DONE.** `controller/BleController.kt` implements
  the unchanged `BluetoothController` interface using `BluetoothLeScanner` for
  discovery (adapter state / enable / disable / forget / bonded-LE list identical to
  Classic). `AppContainer.bluetoothController` now returns the BLE or Classic
  controller to match `useBleTransport`, so the existing pairing sheet
  (`BluetoothQuickSheet` / `DeviceScanScreen`) and `BluetoothViewModel` list and
  connect BLE devices **with no UI change**. Discovery is unfiltered (the controller
  is unnamed and may not advertise its service UUID); `BleServiceScanner` reconnects
  by address, falling back to a service-UUID advertiser.
- **Phase 3 — Wire into the normal connect flow** ✅ **DONE (persistence + normal
  connect path); one item firmware-gated.**
  - The *normal* Home → "Connect Bluetooth" → sheet → tap-device path connects over
    BLE through `TelemetryService` (foreground service) — no separate BLE UI.
  - **Transport choice is now persisted** (`DevicePreferences.bleTransport`) and
    **applied at startup** (`DisplayApp.onCreate`), so BLE survives a restart. The
    Developer toggle writes the preference.
  - The activation toggle stays under Developer (persisted, not ephemeral) rather than
    end-user Settings **on purpose** until it's validated on hardware — surfacing an
    unvalidated transport to riders risks bricking their connection. Trivial to promote
    once green.
  - **Rotating-address auto-reconnect** is as robust as the firmware allows:
    `BleServiceScanner` reconnects by the live address, falling back to a service-UUID
    advertiser. Reliable auto-reconnect after the RPA rotates **requires the firmware
    to advertise the service UUID** (or a stable name) — see
    `docs/BLE_FIRMWARE_REQUIREMENTS.md` §1.3 / §2. Until then, reconnect works within a
    session and while the address is unchanged.
- **Phase 3b — Aligned to the firmware spec + productionized** ✅ **DONE.** The firmware
  team's `docs/EVDISPLAY_BLE_COMMUNICATION.md` confirmed the controller advertises
  service `0xAF00`, has a **public/stable address**, and is named **`EVdisplay`** — which
  dissolves the earlier "rotating address / unadvertised UUID" blockers (an unrelated
  Fast-Pair device had confused the probe). Changes:
  - Both scanners (`BleServiceScanner`, `BleController`) now use a **`ScanFilter` on
    `0xAF00`** → reliable discovery, screen-off-capable reconnect, and the sheet lists
    "EVdisplay" instead of every BLE device.
  - **BLE is now the default transport** (`AppContainer.useBleTransport = true`); SPP is
    retained as a code-level rollback (flip the flag).
  - Removed the experimental Developer "BLE transport" harness + its persisted flag
    (the normal Home → Connect Bluetooth flow now uses BLE). The BLE GATT **probe** is
    kept as a diagnostic.
  - Reverted `CURRENT_CHANNEL_CALIBRATED` to `false` (real `motorCurrentRaw` is ~0 until
    calibrated → show "—"); unit tests green.
  - Decode verified: the firmware §5e golden frame is byte-identical to the passing
    `VotolTelemetryFrameTest`.
- **Phase 4 — Field-test on real hardware** ✅ **DONE.** Confirmed a live connection to
  the `EVdisplay` controller over BLE via the normal Home → Connect Bluetooth flow;
  telemetry renders through the unchanged pipeline.
- **Phase 5 — Retire the SPP classes** ✅ **DONE.** Deleted `SppDataSource`,
  `SppSocketClient`, the Classic `BluetoothScanner`, and `AndroidBluetoothController`;
  simplified `AppContainer` (no transport flag/selector — `createDelegate` is
  simulator-or-BLE, `bluetoothController` is `BleController`); set
  `uses-feature bluetooth_le` to `required="true"` and refreshed the manifest comments.
  Kept the shared `ReconnectPolicy`, `Crc16`, protocol layer, and `SwitchableDataSource`
  (still used for the simulator↔BLE swap). Compiles; full unit-test suite green.

  > **Follow-up:** `CLAUDE.md` still describes the transport as Bluetooth Classic
  > SPP/RFCOMM in several places (architecture table, Gotchas, the frame-watchdog note).
  > It should be updated to describe BLE/GATT — not done here to keep this change scoped.

---

## 5a. BLE audit (post-Phase-3) — findings & status

Adversarial audit of the BLE transport.

**Firmware-gated blockers (cannot be fixed in the app alone):**
- **B1 — Screen-off reconnect is broken.** Android does not deliver **unfiltered**
  scan results while the screen is off; a `ScanFilter` is required. With a rotating
  RPA and (per probe) no advertised service UUID, no filter can match — so a drop with
  the screen off can't reconnect, defeating the foreground-service "record with screen
  off" case. **Fix requires firmware to advertise service `0xAF00`** → then we scan
  with a `ScanFilter` (screen-off capable).
- **B2 — No stable identity ⇒ no durable auto-reconnect.** A resolvable-private
  address is re-identifiable only via the bonding IRK; the app doesn't bond and the
  service UUID isn't advertised, so after the address rotates neither the saved MAC nor
  a scan can find the controller. **Fix: firmware advertises the service UUID, or uses
  a public/static address, or the app bonds.**

Both are documented for firmware in `BLE_FIRMWARE_REQUIREMENTS.md` §1.3 / §2.

**App-side bugs fixed in this pass:**
- Re-`connect()` leaked the previous `BluetoothGatt` / left a stray callback → now
  cancels the job **and** closes the old client before a new attempt.
- Concurrent loss paths (watchdog / onDisconnected / scan-fail) could spawn **parallel
  reconnect loops** → `handleConnectionLost` now cancels the prior attempt first.
- Watchdog false-tripped on a **slow first notification** → added an initial-data grace
  (`INITIAL_DATA_TIMEOUT_MS`) until the first byte arrives.
- `setCharacteristicNotification(...)` return value was ignored → now fails the connect
  cleanly instead of "subscribed but silent".
- Shared flags marked `@Volatile`; adapter-off handling posted onto the connection
  scope (off the binder thread).

**Known residual (non-blocking, tracked):**
- Full single-thread confinement of `BleDataSource` state (belt-and-suspenders over the
  `@Volatile` + idempotent-close approach).
- Rapid reconnect scans could hit Android's 5-scans/30s throttle in a tight window.
- Neither `BluetoothController` impl calls `release()` on transport switch (pre-existing
  singleton pattern; receivers persist for process life).
- No bonding-required fallback (matches the observed "no bond" profile).

---

## 6. Top risks

1. **Firmware GATT server does not exist / profile undefined** (Phase 0) — biggest,
   and outside the Android codebase.
2. **Random BLE addresses** breaking reconnect-by-MAC — may force a
   reconnect-by-scan model and a small tweak to `DevicePreferences`/auto-reconnect
   (still below the ViewModel layer).
3. **GATT op-serialization bugs** — mitigated by a disciplined `BleGattClient`.
4. **MTU/fragmentation** — already mitigated by `FrameDecoder`.

---

## 7. File layout

```
data/bluetooth/
├── BluetoothDataSource.kt        (unchanged interface)
├── SwitchableDataSource.kt       (unchanged — swap point)
├── ble/
│   ├── BleConstants.kt           ✅ Phase 1 — UUIDs (0xAF00/0xAF08/0xAF07) + MTU
│   ├── BleGattClient.kt          ✅ Phase 1 — serialized GATT handshake, notify→bytes
│   ├── BleServiceScanner.kt      ✅ Phase 1 — find by service UUID (rotating address)
│   ├── BleDataSource.kt          ✅ Phase 1 — BluetoothDataSource via GATT
│   ├── BleServiceScanner.kt      ✅ Phase 1/2 — reconnect + connect-time device lookup
│   └── BleGattProbe.kt           (diagnostic — Developer probe/listen)
└── controller/
    └── BleController.kt          ✅ Phase 2 — BluetoothController via BluetoothLeScanner
```

Also changed in Phase 1: `AppContainer` (`useBleTransport` + `createDelegate`),
`AndroidManifest.xml` (`uses-feature bluetooth_le`), and a Developer-screen
"BLE transport (experimental)" harness.

`SppDataSource`, `SppSocketClient`, Classic `BluetoothScanner`, and
`AndroidBluetoothController` are retained until Phase 5 (dual-transport / rollback).

---

**Bottom line:** a clean, well-scoped migration — a new transport implementation
behind an interface that already exists, with UI / ViewModels / Repository /
protocol untouched. The gating item is entirely on the firmware side (§3).
