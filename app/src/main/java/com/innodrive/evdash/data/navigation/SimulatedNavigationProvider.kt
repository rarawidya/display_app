package com.innodrive.evdash.data.navigation

import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Maneuver
import com.innodrive.evdash.domain.model.NavProgress
import com.innodrive.evdash.domain.model.NavState
import com.innodrive.evdash.domain.model.RoutePlan
import com.innodrive.evdash.domain.repository.NavigationProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hardware-free [NavigationProvider] that plays a short scripted route (depart →
 * approach → turn left → arrive). Lets the `RouteNavigator` → encoder → BLE path be
 * demoed and tested with no routing SDK, mirroring how [SimulatedDataSource] stands
 * in for the real BLE link.
 *
 * NOT the production provider — a real SDK adapter replaces it later; RouteNavigator
 * and everything downstream stay unchanged.
 */
class SimulatedNavigationProvider(
    private val routeId: Long = 0x11223344L,
) : NavigationProvider {

    private val _progress = MutableSharedFlow<NavProgress>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val progress: Flow<NavProgress> = _progress.asSharedFlow()

    @Volatile private var running = false

    override suspend fun start(destination: GeoLocation, initialPlan: RoutePlan?) {
        // Scripted route — an initial plan has nothing to adopt. Drop the previous
        // session's replayed terminal state so a re-run isn't killed at birth.
        _progress.resetReplayCache()
        running = true
        val total = 5300
        // Depart.
        emit(state = NavState.Navigating, maneuver = Maneuver.Depart, street = "Jl. Sudirman",
            toTurn = 800, remaining = total, eta = 840, speed = 0)
        // Approach the turn — countdown at speed.
        var toTurn = 800
        var remaining = total
        while (running && toTurn > 0) {
            delay(1_000)
            toTurn = (toTurn - 60).coerceAtLeast(0)
            remaining = (remaining - 60).coerceAtLeast(0)
            emit(state = NavState.Navigating, maneuver = Maneuver.TurnLeft, street = "Jl. Thamrin",
                toTurn = toTurn, remaining = remaining, eta = remaining / 6, speed = 22,
                next = Maneuver.TurnRight, nextDist = 300)
        }
        if (!running) return
        // Arrive.
        emit(state = NavState.Arrived, maneuver = Maneuver.Arrive, street = "",
            toTurn = 0, remaining = 0, eta = 0, speed = 0)
        running = false
        _progress.resetReplayCache() // don't replay the terminal into the next run
    }

    override fun stop() {
        if (!running) return
        running = false
        emit(state = NavState.Cancelled, maneuver = Maneuver.None, street = "",
            toTurn = 0, remaining = 0, eta = 0, speed = 0)
        _progress.resetReplayCache() // don't replay the terminal into the next run
    }

    private fun emit(
        state: NavState, maneuver: Maneuver, street: String,
        toTurn: Int, remaining: Int, eta: Int, speed: Int,
        next: Maneuver = Maneuver.None, nextDist: Int = 0,
    ) {
        _progress.tryEmit(
            NavProgress(
                routeId = routeId,
                state = state,
                maneuver = maneuver,
                distanceToTurnM = toTurn,
                streetName = street,
                distanceRemainingM = remaining,
                etaSeconds = eta,
                nextManeuver = next,
                nextDistanceM = nextDist,
                speedLimitKmh = 50,
                speedKmh = speed,
                totalDistanceM = 5300,
                totalDurationSec = 840,
                destinationName = "Kantor",
                maneuverCount = 4,
            ),
        )
    }
}
