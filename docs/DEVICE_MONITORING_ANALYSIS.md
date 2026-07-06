# Device Monitoring Readiness — Gap Analysis

**Target device:** EV two-wheeler (electric scooter) **instrument cluster**, running on
embedded hardware (MicroPython and C/C++ builds of the same firmware — see
`display_image/`).

**Companion app:** DisplayApp / EVDash monitors the cluster over Bluetooth-Classic
SPP/RFCOMM.

**Key finding:** the cluster is **bidirectional** — it displays telemetry *from* the
vehicle **and** phone-sourced data (notifications, navigation, clock) *pushed to it*.
The app today only implements the read direction. To be "ready to monitor these
display devices," it needs parity on the telemetry the cluster surfaces **plus** an
uplink for the phone-fed panels.

---

## 1. Feature parity map (cluster vs. app)

| Cluster surfaces (from images) | App status | Priority |
|---|---|---|
| Speed, RPM, battery %, motor/batt/controller temp, range, drive mode | ✅ Fully monitored | — |
| **Warning telltale row** (brake fault, battery fault, service/MIL, motor-overheat, ABS, L/R turn signals, high beam, eco/regen, hill mode) | ❌ None | **P0** |
| **Odometer (ODO)** | ❌ Retired; dead `DashboardUiState.odometer` field | **P0** |
| **Turn-by-turn navigation** (next maneuver, distance-to-turn, speed-limit sign, real ETA) | ⚠️ Straight-line stub only | **P1** |
| **Phone notifications on cluster** (WhatsApp / Telegram / SMS / call) | ❌ No SPP uplink at all | **P1** |
| On-device clock / time sync | ❌ Not sent to device | **P2** |
| Drive-mode name "URBAN" | ⚠️ App calls wire value 2 "NORMAL" | **P2** |

---

## 2. The three structural gaps

### P0 — No warning-lamp / fault monitoring (most important)

The cluster's entire top row is safety telltales. The app monitors none of them, yet
the infrastructure is **half-built and dead**:

- `DashboardUiState.leftIndicator / rightIndicator / headlamp` and `odometer` exist
  but have **zero writers/readers** (`DashboardUiState.kt:30-34`).
- `FaultEventEntity` + `FaultEventDao` + retention are fully wired into Room /
  `AppContainer`, but `TripSessionManager.logFault()` (`TripSessionManager.kt:182`)
  **has no callers** — no fault is ever recorded or displayed.
- `EvIcons` has **no telltale glyphs** (brake, battery-warning, MIL, ABS, high-beam,
  turn signal).

**To close it:**
1. Re-add an **indicators/fault bitmask** as a *new* Cap'n Proto wire field. The schema
   explicitly forbids reusing retired slots @6 (odometer) / @8 (indicators) — take a
   fresh field number.
2. Decode it in `TelemetryMapper`.
3. Add telltale icons to `EvIcons` (raw `ImageVector` path data — do **not** add
   `material-icons-extended`).
4. Render a telltale row on the Drive page.
5. Feed critical faults into the existing `FaultEventEntity` pipeline (call the
   already-present `logFault()`).
6. Raise a `POST_NOTIFICATIONS` alert when a new critical lamp appears.

This is precisely CLAUDE.md's "Phase 4 wires reconnect events into `FaultEventEntity`
for persisted fault tracking."

### P0 — No absolute odometer

The cluster shows ODO prominently; the app only integrates **per-trip** distance
(`TripSessionManager`, `EfficiencyTracker`) and never accumulates lifetime mileage.

**To close it:** either add an odometer wire field (new slot) or maintain a persisted
lifetime accumulator in Room; surface it on Drive + Logs; populate the dead
`DashboardUiState.odometer` field.

### P1 — SPP link is read-only (no uplink)

`BluetoothDataSource` exposes only `incomingData`; `SppSocketClient.write()` exists but
is **dead code with no callers**. The cluster's notification / nav / clock panels can
only be fed by the phone.

**To close it:** add a `send()`/`write()` path on the data source, define a phone→device
frame type, and add producers for:
- (a) `NotificationListenerService`-sourced app notifications,
- (b) turn-by-turn maneuvers,
- (c) time sync.

Turn-by-turn also needs real routing — `MapsViewModel.computeRouteStub`
(`MapsViewModel.kt:92`) is a haversine straight line with hardcoded 40 km/h, no
maneuvers, and no speed limit.

---

## 3. Smaller items

- **Drive-mode naming:** cluster uses ECO / **URBAN** / SPORT; app maps wire `2 → NORMAL`.
  Rename to URBAN for parity (`TelemetryMapper.kt:56`, `VehicleMode`).
- **Real ETA / battery-at-arrival** in the nav ETA card (currently hardcoded `"—%"`,
  `NavigationScreen.kt:414`).

---

## 4. Recommended sequencing

1. **P0 warning-lamp / fault feature first.** It's the difference between a telemetry
   viewer and a genuine monitoring companion, and most of the plumbing (fault DB, dead
   UI-state fields) is already there waiting to be connected.
2. **P0 odometer** alongside it (same schema + Room migration cycle).
3. **P1 uplink + turn-by-turn** as a second effort (bidirectional protocol is a larger
   surface).
4. **P2 naming / clock / ETA polish.**

All wire changes require **new** Cap'n Proto slot numbers plus a Room `Migration`
appended to `TelemetryDatabase.MIGRATIONS` (destructive fallback is disabled on purpose).
