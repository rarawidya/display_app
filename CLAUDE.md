# DisplayApp

Android app that connects to a Bluetooth Classic (SPP/RFCOMM) device, streams Cap'n Proto telemetry frames, persists them via Room, and renders them in a Jetpack Compose UI. Targets Android 16 QPR1 (compileSdk `36.1`); minSdk 24.

## Build & run

```bash
./gradlew assembleDebug                                  # build debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk # install on a connected device/emulator
adb shell am start -n com.example.displayapp/.MainActivity
```

Build toolchain:
- AGP `9.1.1` (bleeding edge — requires Android Studio Narwhal+ / Otter Canary)
- Kotlin `2.2.10`, Gradle `9.3.1`, JDK 11 source/target
- KSP for Room compiler
- Bundled BoM: `androidx.compose:compose-bom:2024.09.00`

Emulator note: the AVD must be **API 36.1 / Android 16 QPR1** to match `compileSdk`. If the emulator gets stuck at boot (PackageManager service missing, `pm list packages` returns `Can't find service: package`), cold-boot with wipe-data:

```bash
adb -s <serial> emu kill
~/Android/Sdk/emulator/emulator -avd <AvdName> -no-snapshot-load -wipe-data
```

## Architecture

Clean-architecture layering under `app/src/main/java/com/example/displayapp/`:

- `data/` — sources of truth and IO
  - `bluetooth/{scanner,connection}` — Bluetooth Classic SPP (RFCOMM) scan + socket lifecycle (`SppSocketClient`, `ReconnectPolicy`)
  - `protocol/` — Cap'n Proto telemetry framing (schema in `app/schema/telemetry.capnp`) + `Crc16` integrity check
  - `persistence/{entity,dao,export}` — Room schema, queries, and CSV export
  - `preferences/` — DataStore-backed user preferences
  - `simulator/` — fake data source for development without hardware
  - `system/` — OS integration helpers
  - `repository/` — concrete repository implementations
- `domain/{model,repository}` — pure Kotlin domain types and repository interfaces consumed by `presentation/`
- `presentation/`
  - `viewmodel/` — `ViewModel`s holding UI state flows
  - `state/` — UI state data classes
  - `navigation/` — Navigation Compose graph
  - `ui/{dashboard,connection,device,charts,logs,settings,permissions,components,common,icons}` — screens and reusable composables
- `service/TelemetryService` — foreground service (`foregroundServiceType="connectedDevice"`) that owns the live BLE session so it survives recomposition / backgrounding
- `di/` — manual DI wiring
- `ui/theme/` — Material3 theme
- `DisplayApp.kt` — `Application` subclass (Timber init, DI bootstrap)
- `MainActivity.kt` — single-activity host for the Compose nav graph

Room schemas are versioned under `app/schema/` (exported by the Room KSP processor — keep these committed and review diffs on schema changes).

## Permissions

Declared in `app/src/main/AndroidManifest.xml`:

- Bluetooth: legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` (maxSdk 30), modern `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`
- `ACCESS_FINE_LOCATION` — required for BLE scan on older API levels
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` — for `TelemetryService`
- `POST_NOTIFICATIONS` — Android 13+ foreground-service notification

The runtime permission flow lives in `presentation/ui/permissions/`.

## Conventions

- Compose-only UI; no XML layouts.
- Coroutines + `Flow` for async; ViewModels expose `StateFlow`.
- Logging via Timber (`Timber.tag(...).d(...)`).
- Hardware-free development path: use the `data/simulator/` source instead of real BLE.
- When adding a Room entity/DAO, run a build to regenerate the schema JSON in `app/schema/` and commit it.

## Local config

`local.properties` is per-machine (contains `sdk.dir`) and is gitignored — do not commit it.
