@0xf0e5f2ff4f178d2a;

# EVDISPLAY (Votol) Telemetry Frame Schema — App-facing protocol.
#
# This is the canonical, FROZEN schema published by the firmware team in
# `capnp.md` (v1, 2026-07-03). The app decodes exactly these fields off the
# Bluetooth-Classic SPP stream. Do NOT renumber, reorder, retype, or delete
# ordinals @0..@10 — new telemetry (odometer, throttle, BMS, timestamps, turn
# signals…) is APPENDED at @11+, which grows the frame LEN.
#
# Board → phone, telemetry uplink only (~10 Hz). No downlink is defined in v1.
#
# ─────────────────────────────────────────────────────────────────────────────
#  Wire framing (see capnp.md §2)
# ─────────────────────────────────────────────────────────────────────────────
#   [SYNC:0xAA] [LEN:uint8] [PAYLOAD:LEN bytes] [CRC16-CCITT:uint16-LE]
#
#   SYNC   = 0xAA single frame-start byte (scan for it to resync).
#   LEN    = payload length in bytes (uint8). Currently 40; grows if the schema
#            gains fields. ALWAYS trust LEN — never hardcode 40.
#   CRC16  = CRC16-CCITT (poly 0x1021, init 0xFFFF, MSB-first, NO final XOR),
#            computed over LEN || PAYLOAD, transmitted little-endian.
#   Total frame length = LEN + 4 bytes.
#
#   PAYLOAD is one unpacked, single-segment Cap'n Proto `VotolTelemetry`
#   message: 8-byte segment header + 8-byte root pointer + 3 data words (24 B).
#
# ─────────────────────────────────────────────────────────────────────────────
#  Field semantics / trust (see capnp.md §3)
# ─────────────────────────────────────────────────────────────────────────────
#   batteryDeciVolts  0.1 V units (÷10 → volts).                     CONFIRMED
#   currentMotor      deci-amps (÷10 → A), signed (neg = regen).     CONFIRMED
#   rpm               real motor rpm (already scaled).               CONFIRMED
#   speedKmh          km/h, direct (firmware derives rpm*83/1000).   PROVISIONAL
#   controllerTempC   whole °C, direct.                             CONFIRMED
#   motorTempC        whole °C, direct.                              CONFIRMED
#   driveMode         1 / 2 / 3 (Eco / Urban / Sport).               CONFIRMED
#   faultCode         controller fault bitfield, currently 0.        PROVISIONAL
#   flags             bitfield (see below).                          CONFIRMED
#   seq               rolling frame counter (drop detection).
#   batteryPercent    0-100 % SoC, or 255 = NOT-YET-KNOWN (show --). CONFIRMED
#
#   `flags` bits (UInt8): 0 run · 1 brake · 2 moving · 3 reverse ·
#                         4 park · 5 sideStand (N/A, firmware forces 0) ·
#                         6 lowBattery (SoC≤15%) · 7 regen (currentMotor < 0).
#
# NOTE — divergences from the app's previous internal schema, kept here so the
# canonical-invariant docs stay honest:
#   • rpm and speedKmh are now REAL wire fields — the app no longer derives
#     rpm = speed×100. Both are read straight off the frame.
#   • currentMotor is CONFIRMED signed deci-amps (÷10 = A, negative = regen) —
#     matched against the vendor display. Bus power (V×I), the energy/regen
#     integrators and Wh/km + range are therefore LIVE. (~−4 count ≈ 0.4 A
#     zero-offset at idle; small enough that the reader applies ÷10 with no
#     offset subtraction, per the firmware capnp.md reference decode.)
#   • battery pack temperature is NOT on this wire (v1). VehicleData keeps the
#     field for UI/persistence continuity but the mapper sets it 0 (unknown).

struct VotolTelemetry {
  batteryDeciVolts @0 :UInt16;   # battery voltage, 0.1 V units (809 = 80.9 V)
  currentMotor     @1 :Int16;    # motor current, deci-amps (÷10 = A), signed (neg = regen)
  rpm              @2 :UInt16;   # real motor rpm (already scaled)
  speedKmh         @3 :UInt16;   # km/h (firmware: rpm * 83 / 1000)
  controllerTempC  @4 :Int8;     # controller temp, whole deg C
  motorTempC       @5 :Int8;     # motor temp, whole deg C
  driveMode        @6 :UInt8;    # 1 = Eco, 2 = Urban, 3 = Sport
  faultCode        @7 :UInt32;   # controller fault bitfield (currently 0)
  flags            @8 :UInt8;    # bit0 run, bit1 brake, bit2 moving, bit3 reverse, bit4 park, bit5 sideStand, bit6 lowBattery, bit7 regen
  seq              @9 :UInt32;   # rolling frame counter (drop detection)
  batteryPercent   @10 :UInt8;   # state-of-charge 0-100 %, or 255 = not yet known
}
