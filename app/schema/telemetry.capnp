@0xf0e5f2ff4f178d2a;

# EVDISPLAY (Votol) Telemetry Frame Schema — App-facing protocol.
#
# Canonical schema published by the firmware team in `docs/capnpble.md` §3.
#
# ⚠️ 2026-07-07 WIRE-BREAKING RENUMBER. `VotolTelemetry` was re-sorted to
# sequential ordinals @0..@14 (declaration order) and gained `batteryCurrent`
# (@2), `tempBattery` (@8), `odoMeters` (@12), `tripMeters` (@13). Every byte
# offset moved; the data section grew 24→32 bytes (3→4 words) and the framed
# payload 40→48 bytes (LEN 0x30). Ordinals @0..@14 are now FROZEN — new
# telemetry is APPENDED at @15+, which grows the frame LEN.
#
# APPEND-ONLY EXTENSIONS (docs/capnpble_new.md §3, all @0..@23 now FROZEN):
#   2026-07-07  trip A/B  : tripAMeters @15, tripBMeters @16  (data 32→40 B / LEN 0x38)
#   2026-07-13  OTA       : fwVersion @17, otaState @18, otaProgress @19 (→ 48 B / LEN 0x40)
#   2026-07-14  multi-motor: phaseA @20, phaseC @21, power @22, controllerType @23
#                           (data 48→56 B / LEN 0x48, framed 76 B)
# `tripMeters` @13 is now a LEGACY ALIAS that always mirrors `tripAMeters` @15.
# @17..@23 are BOARD→app TX-only (the STM32/UART sender fills them 0). Old readers
# that know only @0..@14/@16/@19 keep working — decode by ordinal, always honor LEN,
# a shorter frame reads the missing trailing fields as 0.
#
# Board → phone, telemetry uplink (~10 Hz) over BLE GATT Notify on 0xAF08.
# (Phone → board is the separate `PhoneNotification` schema written to 0xAF07.)
#
# ─────────────────────────────────────────────────────────────────────────────
#  Wire framing (see capnpble.md §2)
# ─────────────────────────────────────────────────────────────────────────────
#   [SYNC:0xAA] [LEN:uint8] [PAYLOAD:LEN bytes] [CRC16-CCITT:uint16-LE]
#
#   SYNC   = 0xAA single frame-start byte (scan for it to resync).
#   LEN    = payload length in bytes (uint8). Currently 48 (0x30); grows if the
#            schema gains fields. ALWAYS trust LEN — never hardcode 48.
#   CRC16  = CRC16-CCITT (poly 0x1021, init 0xFFFF, MSB-first, NO final XOR),
#            computed over LEN || PAYLOAD, transmitted little-endian.
#   Total frame length = LEN + 4 bytes.
#
#   PAYLOAD is one unpacked, single-segment Cap'n Proto `VotolTelemetry`
#   message: 8-byte segment header + 8-byte root pointer + 4 data words (32 B).
#
# ─────────────────────────────────────────────────────────────────────────────
#  Field semantics / trust (see capnpble.md §3)
# ─────────────────────────────────────────────────────────────────────────────
#   batteryVolt       0.1 V units (÷10 → volts).                      CONFIRMED
#   batteryPercent    0-100 % SoC, or 255 = NOT-YET-KNOWN (show --).  CONFIRMED
#   batteryCurrent    whole A, signed, positive = charging pack.      CONFIRMED
#   currentMotor      deci-amps (÷10 → A), signed (neg = regen).      CONFIRMED
#   rpm               real motor rpm (already scaled ×4.5).           CONFIRMED
#   kmh               km/h (firmware derives raw*83/1000).            PROVISIONAL
#   tempControl       whole °C, direct.                               CONFIRMED
#   tempMotor         whole °C, direct.                               CONFIRMED
#   tempBattery       battery pack whole °C, direct.                  CANDIDATE
#   driveMode         1 / 2 / 3 (Eco / Urban / Sport).                CONFIRMED
#   faultCode         controller fault bitfield, currently 0.         PROVISIONAL
#   seq               rolling frame counter (drop detection).
#   odoMeters         lifetime odometer, metres (÷1000 → km).         CONFIRMED
#   tripMeters        resettable trip odometer, metres (÷1000 → km).  CONFIRMED
#   flags             bitfield (see below).                           CONFIRMED
#
#   `flags` bits (UInt8): 0 run · 1 brake · 2 moving · 3 reverse ·
#                         4 park · 5 sideStand (N/A, firmware forces 0) ·
#                         6 lowBattery (SoC≤15%) · 7 regen (currentMotor < 0).
#
#   Derived on the phone (no wire flag): charging = batteryCurrent >= 1
#   (with hysteresis). The odometer trip is resettable via a PhoneNotification
#   control command (category=32, title="ODO_RESET_TRIP") — see capnpble.md §5.

struct VotolTelemetry {
  batteryVolt    @0  :UInt16;   # battery voltage, 0.1 V units (809 = 80.9 V)
  batteryPercent @1  :UInt8;    # state-of-charge 0-100 %, or 255 = not yet known
  batteryCurrent @2  :Int8;     # battery pack current, whole A, signed, positive = charging
  currentMotor   @3  :Int16;    # motor current, deci-amps (÷10 = A), signed (neg = regen)
  rpm            @4  :UInt16;   # real motor rpm (already scaled ×4.5)
  kmh            @5  :UInt16;   # km/h (firmware: raw * 83 / 1000)
  tempControl    @6  :Int8;     # controller temp, whole deg C
  tempMotor      @7  :Int8;     # motor temp, whole deg C
  tempBattery    @8  :Int8;     # battery pack temp, whole deg C
  driveMode      @9  :UInt8;    # 1 = Eco, 2 = Urban, 3 = Sport
  faultCode      @10 :UInt32;   # controller fault bitfield (currently 0)
  seq            @11 :UInt32;   # rolling frame counter (drop detection)
  odoMeters      @12 :UInt32;   # lifetime odometer, metres (÷1000 = km)
  tripMeters     @13 :UInt32;   # LEGACY ALIAS: always mirrors tripAMeters @15, metres (÷1000 = km)
  flags          @14 :UInt8;    # bit0 run, 1 brake, 2 moving, 3 reverse, 4 park, 5 sideStand, 6 lowBattery, 7 regen
  tripAMeters    @15 :UInt32;   # trip meter A, metres (÷1000 = km), resettable (appended 2026-07-07)
  tripBMeters    @16 :UInt32;   # trip meter B, metres (÷1000 = km), independently resettable (appended 2026-07-07)
  fwVersion      @17 :UInt32;   # board firmware, packed semver (major<<16)|(minor<<8)|patch (V1.0.0 = 65536); BOARD→app TX-only
  otaState       @18 :UInt8;    # OTA state enum 0..8 (0 IDLE … 7 SUCCESS, 8 FAILED); BOARD→app TX-only
  otaProgress    @19 :UInt8;    # OTA progress 0..100; BOARD→app TX-only (0 until the board's numeric producer lands)
  phaseA         @20 :Int16;    # motor Phase-A current, deci-amps (÷10 = A), signed; NANJING only (0 on VOTOL)
  phaseC         @21 :Int16;    # motor Phase-C current, deci-amps (÷10 = A), signed; NANJING only (0 on VOTOL)
  power          @22 :UInt16;   # electrical power, WATTS (line current × battery volts); NANJING only (0 on VOTOL)
  controllerType @23 :UInt8;    # 0 = VOTOL (EM-100), 1 = NANJING — the motor/controller MODEL; BOARD-sourced
}
