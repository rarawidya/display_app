@0xb528e8e18b9e73a1;

# EV Telemetry Frame Schema
# Used for real-time vehicle data over Bluetooth Classic SPP.
#
# Wire format: Each Cap'n Proto message is wrapped in a frame:
#   [0xCA 0xFE] [LENGTH:uint16-LE] [PAYLOAD:capnp-message] [CRC16-CCITT:uint16-LE]
#
# All numeric values use fixed-point integers for MCU compatibility.
# To compile for C (STM32/ESP32): capnp compile -oc telemetry.capnp
# To compile for Java:            capnp compile -ojava telemetry.capnp
#
# ─────────────────────────────────────────────────────────────────────────────
#  Protocol invariants (Phase 1 audit) — read alongside CLAUDE.md.
# ─────────────────────────────────────────────────────────────────────────────
#
# CURRENT / REGEN SIGN CONVENTION
#   The signed `current` field carries the *battery's* perspective:
#     current > 0 → discharge (energy LEAVING the pack)
#     current < 0 → regen     (energy ENTERING the pack via regenerative braking)
#   `power = voltage × current` therefore mirrors the same sign — positive
#   under propulsion, negative under regen. Every Wh / range / efficiency
#   calculator in the app assumes this convention; flipping it on the MCU
#   side will silently invert energy-used vs energy-regen totals.
#
# DERIVED FIELDS (RPM, POWER)
#   Neither `rpm` nor `power` is on the wire — both are derived once in
#   `TelemetryDerivations` (data/protocol/TelemetryDerivations.kt) and read
#   off `VehicleData` everywhere else. Definitions:
#     rpm   = speedKmh × 100   (a display proxy, NOT true motor electrical
#                               frequency; if the firmware ever needs real
#                               RPM, add it as a new wire field rather than
#                               overloading this one)
#     power = voltage × current (Watts; sign per the convention above)
#
# TIMESTAMPS
#   The wire `timestamp` field is currently MCU-boot-relative (wraps every
#   ~49 days). The app does NOT use it for absolute time — TelemetryMapper
#   stamps each VehicleData with `System.currentTimeMillis()` at decode.
#   Phase 3 introduces a per-trip `bootEpochMs` so that recorded samples
#   can carry MCU-relative deltas while replay still resolves to absolute
#   wall-clock. Until then, do not rely on `frame.timestamp` for trip math.
#
# RETIRED SLOTS
#   Field numbers @6 (odometer) and @8 (indicators) are intentionally retired
#   — the app integrates distance from speed × dt and no longer surfaces
#   blinker / headlamp state. The slots remain reserved so existing MCU
#   firmware that still emits them continues to decode (bytes silently
#   dropped). New fields MUST take fresh slot numbers; never reuse 6 or 8.
struct TelemetryFrame {
  timestamp             @0 :UInt32;     # Milliseconds since controller boot (NOT wall-clock)
  speed                 @1 :UInt16;     # km/h * 10  (e.g., 45.2 km/h = 452)
  battery               @2 :UInt8;      # State of charge 0-100 %
  voltage               @3 :UInt16;     # Volts * 100 (e.g., 72.50V = 7250)
  current               @4 :Int16;      # Amps * 100  (POSITIVE = discharge, NEGATIVE = regen)
  temperature           @5 :Int8;       # Motor temp in Celsius
  mode                  @7 :VehicleMode;# Current drive mode
  batteryTemperature    @9 :Int8;       # Battery pack temp in Celsius
  controllerTemperature @10 :Int8;      # Motor controller temp in Celsius
}

enum VehicleMode {
  park   @0;
  eco    @1;
  normal @2;
  sport  @3;
}
