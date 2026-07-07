package com.example.displayapp.domain.model

/**
 * Navigation state machine — the lifecycle of a turn-by-turn session as the board
 * needs to understand it. `wire` is the Cap'n Proto enum value sent in
 * `NavInstruction.state`; it is FROZEN and must match `navigation.capnp`. Kotlin
 * declaration order is decoupled from the wire value on purpose (don't rely on
 * `ordinal`).
 */
enum class NavState(val wire: Int) {
    Idle(0),        // no active navigation (also = "clear the nav UI")
    Navigating(1),  // normal turn-by-turn
    Rerouting(2),   // off-route, computing a new route
    OffRoute(3),    // deviated, not yet recomputed
    Arrived(4),     // destination reached
    Cancelled(5);   // user stopped navigation

    /** Terminal states end the session and must be delivered reliably. */
    val isTerminal: Boolean get() = this == Arrived || this == Cancelled
}

/**
 * Provider-neutral maneuver taxonomy. Each routing SDK's own maneuver/banner types
 * are mapped onto this fixed set by the [com.example.displayapp.domain.repository.NavigationProvider]
 * adapter, so the firmware ships one icon table regardless of provider. `wire` is
 * the Cap'n Proto enum value; FROZEN, matches `navigation.capnp`.
 */
enum class Maneuver(val wire: Int) {
    None(0),
    Depart(1),
    ContinueStraight(2),
    TurnSlightLeft(3),
    TurnLeft(4),
    TurnSharpLeft(5),
    TurnSlightRight(6),
    TurnRight(7),
    TurnSharpRight(8),
    UTurn(9),
    KeepLeft(10),
    KeepRight(11),
    Merge(12),
    Roundabout(13),   // pair with NavProgress.roundaboutExit
    RampLeft(14),
    RampRight(15),
    Ferry(16),
    Arrive(17),
}

/**
 * Provider-neutral snapshot of navigation progress emitted by a
 * [com.example.displayapp.domain.repository.NavigationProvider] per GPS fix / SDK
 * tick. `RouteNavigator` converts this into wire messages; nothing below the adapter
 * depends on any routing SDK.
 *
 * All distances are metres, times seconds. `speedKmh` is used only for send-rate
 * throttling (the board interpolates the countdown between frames using its own
 * telemetry speed).
 */
data class NavProgress(
    val routeId: Long,
    val state: NavState,
    val maneuver: Maneuver,
    val roundaboutExit: Int = 0,
    val distanceToTurnM: Int = 0,
    val streetName: String = "",
    val distanceRemainingM: Int = 0,
    val etaSeconds: Int = 0,
    val nextManeuver: Maneuver = Maneuver.None,
    val nextDistanceM: Int = 0,
    val speedLimitKmh: Int = 0,
    /** Ground-truth vehicle/phone speed; drives throttle decisions only. */
    val speedKmh: Int = 0,
    /** Total route figures — used once per route to emit RouteSummary. */
    val totalDistanceM: Int = 0,
    val totalDurationSec: Int = 0,
    val destinationName: String = "",
    val maneuverCount: Int = 0,
)
