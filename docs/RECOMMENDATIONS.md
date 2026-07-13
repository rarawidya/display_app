# DisplayApp — Analysis & Recommendations

_Analysis date: 2026-07-13 · Branch: `map-rider-markers`_

## Overall assessment

This is a **genuinely well-built app**, not a prototype. The clean layering
(data/domain/presentation), provider-agnostic ports (map renderer, routing SDK,
and BLE nav protocol are three independent swappable ports), single-derivation-site
telemetry discipline, and byte-exact golden-frame protocol tests are above the bar
for a cockpit at this stage. The wire pipeline, notification/call relay, call
control, board Wi-Fi push, navigation, and energy accumulation are all really
implemented — not stubs.

The gaps are concentrated in four areas: **dormant features that are ~80% wired but
never triggered**, **thin runtime-logic test coverage**, **network/BLE robustness**,
and **release engineering**. Below is what to add, ordered by return on effort —
plus, importantly, what *not* to add.

---

## 1. Finish what's already built (highest ROI — nearly free)

These features have models, DAOs, DI wiring, and sometimes UI already in place.
They just aren't connected end-to-end. Finishing them is cheap and removes dead code.

| Feature | Current state | What's missing |
|---|---|---|
| **Fault history** | `FaultEventEntity` + `FaultEventDao` + Room table + migration + retention all exist and are DI-wired; `TripSessionManager.logFault()` writes rows | **`logFault()` has zero callers** and no UI reads the DAO. Wire the fault-decode in `DashboardViewModel` to call it on a `faultCode` transition, and add a fault-history list (Logs tab or Trip Detail). |
| **Named fault decoding** | Generic "fault lamp" lights when `faultCode != 0` | Decode the bitfield into named faults (overtemp, overcurrent, low-voltage…) per the Votol spec. `OverlayKind` in `TelemetryPalette` was designed for exactly this. |
| **Persist `faultCode`/`flags`** | Decoded live but `VehicleData.kt:49` notes they're *not* persisted; `decodeEntity` leaves defaults | One Room migration + two columns so replay/CSV/Trip Detail show faults for recorded trips. |
| **Energy cost** | `EnergyCostCalculator` math is complete and correct but **dormant** — callers pass `ratePerKwh = null`, so it never renders | Add a per-kWh rate input in Settings; the cost stat then lights up on the Energy Summary card automatically. |
| **Vehicle identity** | Name/metadata hardcoded in `HomeScreen.kt:976` and `VehicleRowCard.kt` | Read the BLE Device Information Service (or a config field) into a small domain model. |

**Doc drift:** CLAUDE.md is stale in two spots — trip-reset *is* wired
(`DashboardViewModel.resetTripOdometer()`), and faults *are* partly surfaced.
Worth a doc pass so the map matches the territory.

---

## 2. Rider-appropriate feature additions

Fit the product (an EV motorbike cockpit) and the existing architecture.
Appropriateness flagged.

### Highly appropriate
- **Charging-station map layer.** `ChargingStation` is already a placeholder model,
  and MapLibre + GraphHopper are in place. A live charging POI overlay
  (e.g. Open Charge Map API) is the natural next map feature for an EV.
- **Range-to-empty / low-battery alerting.** `EfficiencyTracker` already produces
  range estimates — surfacing a "can I make it?" indicator against the active route
  distance is high value and uses existing data.
- **Trip statistics & history polish.** Rides are persisted; weekly/monthly summaries,
  efficiency trends, and best/worst-ride views are cheap given the Room layer.
- **Screen keep-awake on Drive.** There is **no `keepScreenOn`/wake-lock anywhere**.
  For a handlebar-mounted cockpit the screen will sleep mid-ride — a real usability miss.

### Appropriate but scoped
- **Real OTA firmware update.** The `OtaUpdateSheet` UI is a complete shell over a
  hardcoded `delay(1800)` that always says "up to date." Only pursue with an actual
  firmware distribution backend — otherwise leave the mock or remove it (a fake
  "up to date" is arguably worse than nothing).
- **Rider profiles / multiple bikes.** Only if there's >1 vehicle; otherwise premature.

### Avoid (scope creep for this product)
- Cloud sync / accounts, social/leaderboard features, voice assistant, in-app music
  control. None fit a focused BLE cockpit and each drags in large surface area
  (auth, backend, privacy). Skip unless a concrete user need appears.

---

## 3. Reliability & robustness (before daily reliance)

- **GraphHopper error handling is lossy.** All failures collapse to `null`/`emptyList`
  via `runCatching{}`. The HTTP status and GraphHopper's JSON `message` body are never
  read — so *quota exceeded*, *bad key*, and *unsupported profile* are indistinguishable
  to the user, and there's **no retry** on a transient drop. Add: read `errorStream`,
  surface a typed error, and one bounded retry.
- **BLE failures carry no reason.** GATT discovery / CCCD failures resolve the connect
  to `false` with no cause; the UI only ever shows DISCONNECTED/RECONNECTING. Thread a
  failure reason up so users can tell "out of range" from "wrong device."
- **No auto-recovery after `maxAttempts=12`.** Once `ReconnectPolicy` concedes, turning
  Bluetooth back on does *not* re-arm reconnect — the user must manually reconnect.
  Consider re-arming on adapter-on.
- **Thread-safety leans on comments, not types.** `BleDataSource`
  (`connectionJob`/`gattClient` mutated from binder callback + caller thread),
  `BleGattClient.close()` racing GATT callbacks, and `TripSessionManager`'s
  non-`@Volatile` running aggregates are all "safe if driven from one dispatcher"
  but not enforced. Confine to a single dispatcher explicitly or add a Mutex.

---

## 4. Testing (the biggest quality gap)

Protocol framing is well tested (golden frames, CRC). But the **runtime logic that
computes what riders see is almost entirely untested:**

- **Zero tests** on: `EnergyAccumulator`, `EfficiencyTracker`, `TelemetryMapper`,
  `TelemetryDerivations`, `TripSessionManager`, `ReconnectPolicy`, GraphHopper JSON
  parsing, **all 10 ViewModels**, and all repositories.
- These are pure and deterministic (no clock reads) — the *easy, high-value* tests.
  A scaling regression in `TelemetryDerivations` silently corrupts every screen, CSV,
  and replay, with nothing to catch it.
- **No Compose UI tests** exist (only the `2+2` scaffold), despite `ui-test-junit4`
  being on the classpath.
- **Blocker:** the test toolchain is missing — no `mockk`, `kotlinx-coroutines-test`,
  `turbine`, or `robolectric` in the version catalog. Add those first; ViewModel/flow/DAO
  tests are unwritable without them.

**Recommendation:** add the test libs, then unit-test the four integrators/mappers
(~a day of work, large safety payoff), then ViewModel tests for `Dashboard`/`Maps`.

---

## 5. Release engineering / production readiness

Currently configured as a debug-only project:

- **No CI** — no `.github/workflows`. Add a build + unit-test workflow.
- **No static analysis** — no detekt/ktlint/Spotless; Android lint is default-only with
  no baseline.
- **No crash reporting** — only local Timber. For a device used while riding, a crash
  reporter (Sentry/Crashlytics) is worth it.
- **Release build is not shippable** — `isMinifyEnabled = false` (no R8 shrink/obfuscation
  despite a referenced `proguard-rules.pro`), **no signing config**, and `versionCode` is
  still `1` / `versionName "1.0"` after ~20 feature commits. Bump versioning per release.
- **Not localizable** — `strings.xml` has **1 entry**; ~32 hardcoded `Text("…")` literals.
  To support other languages, externalize strings now while the surface is small.

---

## Suggested priority order

1. **Wire the fault pipeline + persist faultCode** (dead code → working safety feature; ~1 day).
2. **Add test tooling + unit-test the 4 integrators/mappers** (protects every downstream number).
3. **Drive-screen keep-awake + low-battery/range alert** (immediate daily usability).
4. **GraphHopper typed errors + retry; BLE failure reasons** (robustness).
5. **Energy-cost rate input** (unlocks a built-but-dark feature).
6. **Release hardening**: signing + R8 + versioning + a CI workflow.
7. _Then_ net-new: charging-station map layer.

**Verdict:** the architecture is strong and the hard parts (BLE, protocol, nav) are done —
spend the next effort *finishing dormant features and testing the math*, not adding new
surface. The fault pipeline and the integrator tests are the two cleanest wins.
