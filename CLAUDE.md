# DisplayApp

Android cockpit for a Bluetooth-Classic (SPP/RFCOMM) telemetry source. Decodes Cap'n Proto frames in realtime, persists trips in Room, and renders a Jetpack Compose UI with live charts, map navigation, and trip replay. compileSdk `36.1` (Android 16 QPR1), minSdk 24.

## Build & run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.displayapp/.MainActivity
```

**Toolchain:** AGP `9.1.1` · Kotlin `2.2.10` · Gradle `9.3.1` · Compose BoM `2024.09.00` · JDK 11 source/target · KSP for Room.

**Maps API key (optional):** add `MAPS_API_KEY=AIza…` to `local.properties`. The build inlines it via `manifestPlaceholders` + `BuildConfig.MAPS_API_KEY`. Without a key the app still builds — `MapKeyGate` shows a placeholder instead of the map.

## Architecture

Clean layering under `app/src/main/java/com/example/displayapp/`:

| Layer | Subpackages | Notes |
|---|---|---|
| `data/` | `bluetooth/{scanner,connection,controller}` · `protocol/` · `persistence/{entity,dao,export}` · `energy/` · `preferences/` · `simulator/` · `system/` · `location/` · `replay/` · `sharing/` · `diagnostics/` · `format/` · `permissions/` · `repository/` | SPP socket (`SppSocketClient`) + Cap'n Proto framing + `Crc16`; adapter/bond surface (`AndroidBluetoothController`) feeds the quick-settings sheet independently of the SPP pipeline; Room schema with explicit `Migration` list (no destructive fallback); pure-Kotlin energy integrators (`EnergyAccumulator`, `EfficiencyTracker`); DataStore-backed user prefs (`AppPreferences`); hardware-free simulator with scenarios, fused-location repo, Room-backed trip replay, FileProvider-based CSV sharing, formatters, diagnostics + permission probes. `app/schema/telemetry.capnp` is the Cap'n Proto schema (Room schemas are not exported — `exportSchema = false`) |
| `domain/` | `model/` · `repository/` · `replay/` | Pure Kotlin: `VehicleData`, `GeoLocation`, `Route`, `ChargingStation`, `AppSettings` + unit enums, repository interfaces, `TripReplaySource` |
| `presentation/` | `viewmodel/` · `state/` · `navigation/` · `ui/{dashboard,charts,connection,device,maps,logs,settings,permissions,components,common,icons}` | One ViewModel per cockpit tab + a `RootViewModel` for activity-wide state, `@Immutable` UI states, `EvIcons` (no `material-icons-extended` — saves ~22 MB), reusable `GlassCard`/`SectionHeader`/`StatusChip`. `ui/connection/` hosts the shared Bluetooth surfaces (`BluetoothQuickSheet`, `BluetoothStatusPopover`, `BluetoothDeviceRow`) — both the Drive header and `DeviceScanScreen` drive them through a single `BluetoothViewModel` |
| `service/` | `TelemetryService` | Foreground service (`foregroundServiceType="connectedDevice"`) that owns the live SPP session so it survives backgrounding |
| `di/` | `AppContainer` | Manual DI; constructed once in `DisplayApp.onCreate` |
| `ui/theme/` | `Color`, `Dim`, `Shapes`, `Type`, `Theme`, `TelemetryPalette` | EV-blue Material palette; `TelemetryPalette` is the per-metric color identity (light + dark tunings) provided via `LocalTelemetryPalette`; `Dim` is the only source of dp tokens |

**Single-activity host.** `MainActivity` → `RootViewModel` (theme + `AppSettings`) → `DisplayAppTheme` → `ProvideAppSettings` → `PermissionHandler` → `AppNavHost`. One `Scaffold` + one `NavHost`. Bottom bar (`Drive`/`Chart`/`Logs`) shows only on cockpit routes; `Scan`/`Navigation`/`Settings`/`Developer`/`TripDetail` are full-bleed.

## Notable patterns

- **`LocalAppSettings` CompositionLocal** ([`AppSettingsAmbient`](app/src/main/java/com/example/displayapp/presentation/ui/common/AppSettingsAmbient.kt)) exposes the live `AppSettings` snapshot to any composable. Units (km/h ↔ mph, °C ↔ °F, 12h ↔ 24h) are a *display* concern — converting at the consumer keeps domain types SI and avoids threading prefs through every per-screen ViewModel. Reads are scoped: composables that don't read `LocalAppSettings.current` don't recompose on a unit change. Telemetry/domain is **always** km/h + °C; conversion happens only at the display layer (e.g. `SpeedUnit.convertFromKmh`, `TemperatureUnit.convertFromCelsius`).
- **`RootViewModel`** is the activity-scoped state hub — it merges theme settings + `AppSettings`. Provider tree in `MainActivity` lives outside `AppNavHost` so per-route ViewModels never see preferences.
- **Charts ViewModel** ([`ChartsViewModel`](app/src/main/java/com/example/displayapp/presentation/viewmodel/ChartsViewModel.kt)) uses fixed-capacity `ArrayDeque` ring buffers + 10 fps emit throttle (`EMIT_INTERVAL_MS = 100`). Tweak source-rate via `SOURCE_HZ` if the protocol changes.
- **Multi-line chart Y-axis** uses per-metric normalization with a *focused metric* driving the visible Y tick labels — see [`RealtimeLineChart.niceRange`](app/src/main/java/com/example/displayapp/presentation/ui/charts/RealtimeLineChart.kt). Battery has a fixed `0..100` range; others auto-scale with 12% padding. The focused line is rendered thicker (`FOCUSED_STROKE_DP`) over a soft gradient fill; non-focused selected lines drop to `UNFOCUSED_ALPHA` so the focal series reads first during glance analysis. Y-axis labels are tinted the focused metric's color so the axis ticks self-describe which series they're scaled to.
- **TelemetryPalette is the only source of metric color.** [`TelemetryPalette`](app/src/main/java/com/example/displayapp/ui/theme/TelemetryPalette.kt) maps each [`TelemetryMetric`](app/src/main/java/com/example/displayapp/presentation/state/TelemetryMetric.kt) to a (light, dark) color pair; `DisplayAppTheme` provides the active impl via `LocalTelemetryPalette`. Call sites resolve a color with `metric.seriesColor()` — chip dot, line stroke, marker halo, axis tick label, Drive tile accent, Trip Detail stat label all share one identity per metric. Future overlay treatments (fault outline, thermal warning, regen segment, SoH badge) hang off `OverlayKind` so the visual language scales without scattering raw `Color()` literals across screens.
- **Fullscreen chart** ([`ChartsFullscreenScreen`](app/src/main/java/com/example/displayapp/presentation/ui/charts/ChartsFullscreenScreen.kt)) is a `Dialog` overlay (not a nav route) so it shares the same `ChartsViewModel` instance — state is preserved on entry/exit by definition. Orientation lock + immersive bars in a `DisposableEffect`; `rememberSaveable` keeps the flag alive across config changes. The layout drops the card-shell padding and switches `MetricToggleChips` into `compact = true` so the chart claims maximum landscape real estate.
- **Telemetry replay** (`TripDetail`) swaps the source: `RoomTripReplaySource` produces a `Flow<VehicleData>` from persisted samples, the same chart/dashboard composables consume it. Live vs replay is one repository, two impls.
- **Bluetooth split into two surfaces.** `BluetoothDataSource` + `TelemetryService` own the SPP/RFCOMM socket — the data plane. [`BluetoothController`](app/src/main/java/com/example/displayapp/data/bluetooth/controller/BluetoothController.kt) owns the *adapter/bond* plane: power state, paired list, `ACTION_FOUND` discovery with RSSI, hidden-API `forget`. The controller is independent so opening the quick sheet never touches the live SPP session. Connect/disconnect from the sheet still routes through `TelemetryService` so the foreground notification + lifecycle stay identical whether the user came from the header or `DeviceScanScreen`.
- **Single Bluetooth ViewModel.** [`BluetoothViewModel`](app/src/main/java/com/example/displayapp/presentation/viewmodel/BluetoothViewModel.kt) merges the controller, `VehicleRepository.connectionState`, and `DevicePreferences` into one `BluetoothUiState`. Both the Drive header's [`BluetoothQuickSheet`](app/src/main/java/com/example/displayapp/presentation/ui/connection/BluetoothQuickSheet.kt) and `DeviceScanScreen` instantiate this VM with the same factory args, so the two surfaces stay in sync (scan results, paired list, pending-connect spinner) without an extra repository layer.
- **Header BT icon has two gestures.** Tap → [`BluetoothStatusPopover`](app/src/main/java/com/example/displayapp/presentation/ui/connection/BluetoothStatusPopover.kt) (compact status + reconnect/disconnect). Long-press → `BluetoothQuickSheet` (full management). The popover anchors to the icon via the `bluetoothAnchor` slot in `BrandHeader`; the sheet is a sibling of the cockpit content at the `NavHost` level with `rememberSaveable` flags. Long-press always fires `HapticFeedbackType.LongPress`.
- **Energy split: lifetime vs. rolling.** [`EnergyAccumulator`](app/src/main/java/com/example/displayapp/data/energy/EnergyAccumulator.kt) is a pure trapezoidal V × I × dt integrator owned by `TripSessionManager` — one instance per trip, persisted as `TripEntity.energyUsedWh`/`energyRegenWh`. [`EfficiencyTracker`](app/src/main/java/com/example/displayapp/data/energy/EfficiencyTracker.kt) is a *rolling* 60 s window with EWMA smoothing that drives the Drive page's Wh/km tile and range estimate; it resets on `DISCONNECTED`. Both are deterministic (no clock reads — timestamps come from `VehicleData`) and unit-testable.
- **Map gating** (`MapKeyGate`) reads `BuildConfig.MAPS_API_KEY` and renders a placeholder when empty — avoids hard-coupling demos and dev machines to a paid API key.
- **Developer Mode** is hidden behind tapping the Settings → About → Version row 7 times. Unlock persists in `AppSettings.devModeUnlocked`. Reveals the `Developer` route which surfaces the diagnostics overlay toggle, simulator scenarios, and other runtime probes.

## Gotchas

- **Compose BoM 2024.09.00 + `FlowRow`**: foundation-layout 1.7 lacks the `maxLines`/`overflow` overload that Kotlin resolves against if you import `FlowRow`. Runtime `NoSuchMethodError`. Prefer `Row(Modifier.horizontalScroll(...))` until BoM is bumped past 1.8.
- **`MainActivity` has `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`** — the fullscreen chart relies on this so rotation doesn't destroy the Activity (and the Dialog with it). Removing it will break that flow.
- **Emulator stuck at boot** (API 36.1 in particular): if `adb shell pm list packages` returns `Can't find service: package`, the AVD's PackageManager never started. Recover with:
  ```bash
  adb -s <serial> emu kill
  ~/Android/Sdk/emulator/emulator -avd <AvdName> -no-snapshot-load -wipe-data
  ```
- **Custom Compose icons**: when adding a new icon, extend [`EvIcons`](app/src/main/java/com/example/displayapp/presentation/ui/icons/EvIcons.kt) with raw `ImageVector` path data. Do **not** add the `material-icons-extended` dependency.
- **Trip CSV sharing**: `ShareHelper` uses an authority of `${applicationId}.fileprovider`. Paths exposed are in [`res/xml/file_paths.xml`](app/src/main/res/xml/file_paths.xml) — add new dirs there if a future exporter writes outside cache.
- **Room migrations are real, not destructive.** [`TelemetryDatabase`](app/src/main/java/com/example/displayapp/data/persistence/TelemetryDatabase.kt) declares an explicit `MIGRATIONS` array (currently `MIGRATION_1_2` adding `energyUsedWh`/`energyRegenWh` to `trips`). `fallbackToDestructiveMigration()` is **not** wired up — drop a trip table by accident and you lose user history. Bumping the entity = bumping `version` + appending a `Migration(from, to)`. `exportSchema = false`, so there's no generated JSON to commit; verify migrations by running the app against the prior debug install.
- **`BLUETOOTH_SCAN` declared with `neverForLocation`.** Without it, Android 12+ silently returns `false` from `startDiscovery()` whenever Location Services is off, even with the runtime permission granted — that's what "Couldn't start scan" used to mean. Don't strip the flag: we connect to a known MAC over SPP and never derive location from scan results. `ACCESS_FINE_LOCATION` is now `maxSdkVersion="30"` for the same reason; Android 11 and below still need it for BT discovery.
- **A `BluetoothSocket` input stream ignores `soTimeout`, so a silent link drop never unblocks `read()`.** An MCU watchdog reset or an out-of-range device leaves the read loop blocked forever and reconnect never fires. [`SppDataSource`](app/src/main/java/com/example/displayapp/data/bluetooth/SppDataSource.kt) arms a **frame watchdog** (`runFrameWatchdog`) before the blocking read loop: the read loop stamps `lastDataElapsedMs` (`@Volatile`, monotonic `elapsedRealtime`) on every chunk, and the watchdog polls every `WATCHDOG_POLL_INTERVAL_MS` (1 s) and force-closes the socket once silence exceeds `FRAME_WATCHDOG_TIMEOUT_MS` (3 s ≈ 30 missed frames at the board's ~10 Hz wire rate). The close surfaces as an `IOException` out of `readLoop`, which routes into the normal `handleConnectionLost()` reconnect path. The watchdog is a child of the connection coroutine and is cancelled in the `finally`. Tune `FRAME_WATCHDOG_TIMEOUT_MS` if the wire rate changes — too tight and normal jitter false-trips it.

## Permissions

`AndroidManifest.xml`:

- Bluetooth: legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (maxSdk 30), modern `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (with `neverForLocation` — see Gotchas)
- Location: `ACCESS_FINE_LOCATION` (maxSdk 30, BT discovery on Android 11 and below) + `ACCESS_COARSE_LOCATION` (map blue-dot)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` for `TelemetryService`
- `POST_NOTIFICATIONS` (Android 13+)
- `ACCESS_NETWORK_STATE` (Maps tiles)

Runtime permission flow: `presentation/ui/permissions/`.

## Canonical telemetry invariants (Phase 1 audit)

**One pipeline, one derivation site, one display rule.** These are the rules that
keep Drive / Charts / Logs / Trip Detail / replay / CSV exporting the same
number for the same wire frame.

- **Wire schema is the frozen `VotolTelemetry`.** The board's app-facing spec is [`capnp.md`](capnp.md) (v1, ordinals @0..@10 FROZEN); [`telemetry.capnp`](app/schema/telemetry.capnp) mirrors it. Framing is `[0xAA][LEN:uint8][capnp payload][CRC16-CCITT-LE]`, CRC over `LEN‖payload`, ~10 Hz. New fields append at @11+ and grow `LEN` — always honor `LEN`, never assume 40 bytes.
- **Single decode path.** Live frames flow through `TelemetryMapper` (wire → `VehicleData`); persisted samples flow through [`TelemetryDerivations.decodeEntity`](app/src/main/java/com/example/displayapp/data/protocol/TelemetryDerivations.kt) (Room row → `VehicleData`). Every consumer downstream — replay, CSV, Trip Detail charts — reads the resulting `VehicleData`. `rpm` and `speedKmh` are now **real wire fields** (read straight off the frame, not derived); `power` is still derived in `TelemetryDerivations`. No screen, ViewModel, or exporter may re-derive `power` locally; adding a new derivation = adding a function to `TelemetryDerivations` and calling it from both sites. **Wire scaling ≠ persistence scaling** — the wire carries deci-volts/direct-km/h, while `TelemetryLogger` stores its own fixed-point (×10/×100) that `decodeEntity` inverts via the `*FromEntity` helpers; the two are decoupled so a wire revision never forces a Room migration.
- **Current is uncalibrated (raw counts, currently 0).** `motorCurrentRaw` has no defined scale or sign yet, so `power = voltage × current`, `EnergyAccumulator`, `EfficiencyTracker` (Wh/km, range) and any regen accounting read ~0 on real hardware until firmware calibrates the channel. The simulator injects whole-amp counts so the Drive tiles stay lively. (The old signed discharge/regen convention no longer applies.)
- **rpm is a wire field.** `VotolTelemetry.rpm` (@2) arrives directly; `speedKmh` (@3) is the board's `rpm*83/1000`. The legacy `rpmFromSpeedKmh` (speed×100) survives ONLY as the `decodeEntity` fallback for pre-v6 rows recorded before rpm was persisted.
- **Battery pack temperature is not on the v1 wire.** `TelemetryMapper` sets `VehicleData.batteryTemperature = 0` (unknown); the Battery-Temp tile/series read 0 until firmware adds a channel. The field is retained for UI/persistence continuity.
- **No wire timestamp.** `VehicleData.timestamp` is wall-clock-at-decode. The v1 wire has no timestamp field; `seq` (@9, rolling UInt32) covers frame ordering + drop detection instead.
- **One `dt` clamp.** All integrators (`EnergyAccumulator`, `EfficiencyTracker`, `TripSessionManager` distance, Drive session distance) clamp inter-sample `dt` to [`TelemetryConstants.MAX_SAMPLE_DT_MS`](app/src/main/java/com/example/displayapp/data/protocol/TelemetryConstants.kt). Don't introduce a screen-local clamp; trip distance vs Drive session distance must drift only through different aggregation windows, never through different gap policies.
- **Diagnostics is end-to-end live.** Frame decoding, CRC errors, and sync losses report directly from `FrameDecoder` into `DiagnosticsRepository`; the Dashboard ViewModel passes that snapshot through to `DiagnosticsState` — no zero placeholders, no UI-local accounting. FPS is computed in the VM and pushed via `reportFps()` so Settings and the overlay read the same number.
- **Display-layer unit conversion only.** Chart series stay in SI (km/h, °C); km/h↔mph and °C↔°F are applied at the label site (see `StaticTelemetryChart.displayConverter`). A unit preference change must update labels without rebuilding datasets.

The target invariant: **Drive page == Charts == Logs == Trip Detail == Replay == CSV** for any given wire frame. Phase 2 persisted `rpm`/`power` columns so a future change to the derivation formula doesn't retroactively alter recorded trips; Phase 4 wires reconnect events (and now the wire `faultCode`/`flags`, decoded but not yet surfaced) into `FaultEventEntity` for persisted fault tracking.

## Conventions

- Compose-only UI; no XML layouts.
- Coroutines + `Flow`; ViewModels expose `StateFlow`. UI states are `@Immutable` data classes.
- **Units are SI in the domain, converted at display.** Read `LocalAppSettings.current` and use the unit enum's `convertFromX`/`formatX` helpers rather than mutating telemetry values.
- Logging via Timber (`Timber.tag(...).d(...)`).
- Hardware-free dev: use the `data/simulator/` source instead of real BT.
- When bumping a Room entity, write a new `Migration` and append it to `TelemetryDatabase.MIGRATIONS` — destructive fallback is disabled on purpose. Old rows should default to a value the UI can render as "—" (see how `energyUsedWh = 0.0` is handled) so legacy data isn't misleadingly precise.
- Spacing only via `Dim` tokens — no inline `.dp` literals in screen-level composables.
- **Telemetry series colors only via `TelemetryMetric.seriesColor()`** (or `LocalTelemetryPalette.current` if you need to render outside a composable scope). Don't reach for `EvBlue`/`EvLime`/etc. for anything that represents a plottable channel — the palette already encodes the right per-mode tuning. Status indicators (`Recording`, error tints) can still use the semantic accents directly.

## Local config

`local.properties` is per-machine (`sdk.dir`, `MAPS_API_KEY`) and gitignored — do not commit.
