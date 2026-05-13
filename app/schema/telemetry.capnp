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

# Field numbers @6 (odometer) and @8 (indicators) are intentionally retired —
# the app integrates distance from speed × dt and no longer surfaces blinker /
# headlamp state. The slots remain reserved so existing MCU firmware that still
# emits them continues to decode (the bytes are silently dropped).
struct TelemetryFrame {
  timestamp             @0 :UInt32;     # Milliseconds since controller boot
  speed                 @1 :UInt16;     # km/h * 10  (e.g., 45.2 km/h = 452)
  battery               @2 :UInt8;      # State of charge 0-100 %
  voltage               @3 :UInt16;     # Volts * 100 (e.g., 72.50V = 7250)
  current               @4 :Int16;      # Amps * 100  (negative = regen braking)
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
