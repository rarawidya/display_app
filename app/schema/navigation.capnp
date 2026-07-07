@0xc4e1a9f30b7d2266;

# EVdisplay — Phone→Board NAVIGATION command schema.
#
# Separate from telemetry.capnp (frozen board→phone uplink) and from the
# PhoneNotification command path. Navigation is phone→board, streamed over its
# own BLE characteristic 0xAF06 (see docs/NAVIGATION-INTEGRATION.md).
#
# ─────────────────────────────────────────────────────────────────────────────
#  Wire framing (shared with telemetry / PhoneNotification)
# ─────────────────────────────────────────────────────────────────────────────
#   [SYNC:0xAA] [LEN:uint8] [ navType:uint8 ‖ capnp payload ] [CRC16-CCITT:uint16-LE]
#
#   navType (first payload byte): 0x01 RouteSummary · 0x02 NavInstruction ·
#                                 0x03 RouteChunk
#   CRC16-CCITT (poly 0x1021, init 0xFFFF, MSB-first, no final XOR) over
#   LEN ‖ payload, transmitted little-endian. One frame per BLE write, no
#   reassembly, frame ≤ 244 bytes (payload ≤ 240).
#
# ─────────────────────────────────────────────────────────────────────────────
#  Extensibility contract (READ BEFORE EDITING)
# ─────────────────────────────────────────────────────────────────────────────
#   Ordinals are FROZEN once shipped — never renumber, reorder, retype, or delete.
#   New fields APPEND at the next ordinal; Cap'n Proto guarantees appended fields
#   never move existing ones (they fill trailing padding or grow the struct).
#   Old firmware ignores unknown trailing bytes/words; old phones leave new fields
#   at their default (0 / empty). Honor LEN; never assume a fixed size.
#   `RouteSummary.schemaVersion` gives coarse gating if a breaking change is ever
#   unavoidable.
#
#   Ordinals are assigned in DESCENDING field size (4B → 2B → 1B → Text) so the
#   Cap'n Proto data-section layout is compact and the hand-built writer
#   (NavigationSchema.kt) maps 1:1 to byte offsets. This ordering is a layout
#   convenience only; decode strictly by ordinal/name, never by textual position.

enum NavState {
  idle       @0;   # no active navigation (also = "clear the nav UI")
  navigating @1;   # normal turn-by-turn
  rerouting  @2;   # off-route, computing a new route
  offRoute   @3;   # deviated, not yet recomputed
  arrived    @4;   # destination reached
  cancelled  @5;   # user stopped navigation
}

# Icon/maneuver taxonomy. The phone adapter maps its SDK's maneuver types
# (Mapbox / HERE / Valhalla / ORS) onto this fixed set, so the firmware ships
# ONE icon table regardless of routing provider.
enum Maneuver {
  none             @0;
  depart           @1;
  continueStraight @2;
  turnSlightLeft   @3;
  turnLeft         @4;
  turnSharpLeft    @5;
  turnSlightRight  @6;
  turnRight        @7;
  turnSharpRight   @8;
  uTurn            @9;
  keepLeft         @10;
  keepRight        @11;
  merge            @12;
  roundabout       @13;   # use roundaboutExit for the exit number
  rampLeft         @14;
  rampRight        @15;
  ferry            @16;
  arrive           @17;
}

# navType 0x02 — the ~1 Hz "current maneuver" message. Self-contained (full
# current state, not deltas) so a dropped frame self-heals on the next.
struct NavInstruction {
  routeId            @0  :UInt32;   # active route; bumps on reroute / new route
  distanceRemainingM @1  :UInt32;   # metres to destination
  etaSeconds         @2  :UInt32;   # travel time remaining, seconds
  seq                @3  :UInt16;   # rolling per-instruction counter (stale/order detection)
  state              @4  :NavState;
  maneuver           @5  :Maneuver;
  distanceToTurnM    @6  :UInt16;   # metres to the upcoming maneuver (clamp at 65535)
  nextManeuver       @7  :Maneuver; # look-ahead ("then turn X"); none = unknown
  nextDistanceM      @8  :UInt16;   # metres from THIS maneuver to the next
  roundaboutExit     @9  :UInt8;    # 0 = n/a, else exit number
  speedLimitKmh      @10 :UInt8;    # 0 = unknown (optional cluster nicety)
  streetName         @11 :Text;     # road you turn ONTO (<= 40 UTF-8 B)
}

# navType 0x01 — sent ONCE when navigation starts, and again whenever routeId
# changes. Header + totals for the board; announces polyline chunk count.
struct RouteSummary {
  routeId          @0 :UInt32;
  totalDistanceM   @1 :UInt32;
  totalDurationSec @2 :UInt32;
  maneuverCount    @3 :UInt16;
  polylineChunks   @4 :UInt8;     # number of RouteChunk msgs to expect (0 = none)
  schemaVersion    @5 :UInt8;     # v1 = 1; coarse gating for future evolution
  destinationName  @6 :Text;      # <= 48 UTF-8 B
}

# navType 0x03 — OPTIONAL (phase 2), only if the board draws a route line.
# Downsampled, delta-encoded polyline chunked to fit one frame each. Each chunk
# is self-contained via an absolute anchor, so a lost chunk is recoverable.
struct RouteChunk {
  routeId     @0 :UInt32;
  anchorLatE7 @1 :Int32;          # first point of this chunk, 1e-7 deg
  anchorLonE7 @2 :Int32;
  index       @3 :UInt8;          # 0-based
  total       @4 :UInt8;          # matches RouteSummary.polylineChunks
  deltas      @5 :List(Int16);    # [dLat,dLon,...] in 1e-5 deg (~1.1 m) from running position
}
