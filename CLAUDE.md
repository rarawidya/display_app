# DisplayApp

Android cockpit for a Bluetooth-Classic (SPP/RFCOMM) telemetry source. Decodes Cap'n Proto frames in realtime, persists trips in Room, and renders a Jetpack Compose UI with live charts, map navigation, and trip replay. compileSdk `36.1` (Android 16 QPR1), minSdk 24.

## Build & run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.displayapp/.MainActivity
```

**Toolchain:** AGP `9.1.1` · Kotlin `2.2.10` · Gradle `9.3.1` · Compose BoM `2024.09.00` · JDK 11 source/target · KSP for Room.

**Maps API key (optional):** add `MAPS_API_KEY=AIza…` to `local.properties`. The build inlines it via `manifestPlaceholders` + `BuildConfig.MAPS_API_KEY`. Without a key the app still builds and runs — `MapKeyGate` shows a placeholder instead of the map.

## Architecture

Clean layering under `app/src/main/java/com/example/displayapp/`:

| Layer | Subpackages | Notes |
|---|---|---|
| `data/` | `bluetooth/{scanner,connection}` · `protocol/` · `persistence/{entity,dao,export}` · `preferences/` · `simulator/` · `system/` · `location/` · `replay/` · `sharing/` · `repository/` | SPP socket (`SppSocketClient`), Cap'n Proto framing + `Crc16`, Room schema (versioned under `app/schema/`), DataStore, hardware-free simulator, fused-location repo, Room-backed trip replay, FileProvider-based CSV sharing |
| `domain/` | `model/` · `repository/` · `replay/` | Pure Kotlin: `VehicleData`, `GeoLocation`, `Route`, `ChargingStation`, repository interfaces, `TripReplaySource` |
| `presentation/` | `viewmodel/` · `state/` · `navigation/` · `ui/{dashboard,charts,connection,device,maps,logs,settings,permissions,components,common,icons}` | One ViewModel per cockpit tab, `@Immutable` UI states, `EvIcons` (no `material-icons-extended` — saves ~22 MB), reusable `GlassCard`/`SectionHeader`/`StatusChip` |
| `service/` | `TelemetryService` | Foreground service (`foregroundServiceType="connectedDevice"`) that owns the live SPP session so it survives backgrounding |
| `di/` | `AppContainer` | Manual DI |
| `ui/theme/` | `Color`, `Dim`, `Shapes`, `Type`, `Theme` | EV-blue palette + per-metric semantic colors; `Dim` is the only source of dp tokens |

**Single-activity host.** `MainActivity` → Compose `AppNavHost` → one `Scaffold` + one `NavHost`. Bottom bar (`Drive`/`Chart`/`Logs`) shows only on cockpit routes; `Scan`/`Navigation`/`Settings`/`TripDetail` are full-bleed.

## Notable patterns

- **Charts ViewModel** ([`ChartsViewModel`](app/src/main/java/com/example/displayapp/presentation/viewmodel/ChartsViewModel.kt)) uses fixed-capacity `ArrayDeque` ring buffers + 10 fps emit throttle (`EMIT_INTERVAL_MS = 100`). Tweak source-rate via `SOURCE_HZ` if the protocol changes.
- **Multi-line chart Y-axis** uses per-metric normalization with a *focused metric* driving the visible Y tick labels — see [`RealtimeLineChart.niceRange`](app/src/main/java/com/example/displayapp/presentation/ui/charts/RealtimeLineChart.kt). Battery has a fixed `0..100` range; others auto-scale with 12% padding.
- **Fullscreen chart** ([`ChartsFullscreenScreen`](app/src/main/java/com/example/displayapp/presentation/ui/charts/ChartsFullscreenScreen.kt)) is a `Dialog` overlay (not a nav route) so it shares the same `ChartsViewModel` instance — state is preserved on entry/exit by definition. Orientation lock + immersive bars in a `DisposableEffect`; `rememberSaveable` keeps the flag alive across config changes.
- **Telemetry replay** (`TripDetail`) swaps the source: `RoomTripReplaySource` produces a `Flow<VehicleData>` from persisted samples, the same chart/dashboard composables consume it. Live vs replay is one repository, two impls.
- **Map gating** (`MapKeyGate`) reads `BuildConfig.MAPS_API_KEY` and renders a placeholder when empty — avoids hard-coupling demos and dev machines to a paid API key.

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

## Permissions

`AndroidManifest.xml`:

- Bluetooth: legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (maxSdk 30), modern `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`
- Location: `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (BLE scan on older APIs and map blue-dot)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` for `TelemetryService`
- `POST_NOTIFICATIONS` (Android 13+)
- `ACCESS_NETWORK_STATE` (Maps tiles)

Runtime permission flow: `presentation/ui/permissions/`.

## Conventions

- Compose-only UI; no XML layouts.
- Coroutines + `Flow`; ViewModels expose `StateFlow`. UI states are `@Immutable` data classes.
- Logging via Timber (`Timber.tag(...).d(...)`).
- Hardware-free dev: use the `data/simulator/` source instead of real BT.
- When adding a Room entity/DAO, run a build to regenerate the schema JSON in `app/schema/` and commit the diff.
- Spacing only via `Dim` tokens — no inline `.dp` literals in screen-level composables.

## Local config

`local.properties` is per-machine (`sdk.dir`, `MAPS_API_KEY`) and gitignored — do not commit.
