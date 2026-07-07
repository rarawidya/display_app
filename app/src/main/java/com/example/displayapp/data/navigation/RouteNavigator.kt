package com.example.displayapp.data.navigation

import android.os.SystemClock
import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.protocol.NavigationSchema
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.NavState
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.NavigationProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Orchestrates turn-by-turn navigation → BLE. Subscribes to a provider-neutral
 * [NavigationProvider] and streams compact `NavInstruction` frames (plus a one-shot
 * `RouteSummary` per route) to the controller over the nav characteristic (0xAF06)
 * via [BluetoothDataSource.writeNav].
 *
 * Send policy (docs/NAVIGATION-INTEGRATION.md §5–6):
 *  - **RouteSummary** once per `routeId` (start / reroute) — reliable write.
 *  - **NavInstruction** on maneuver change or state change (immediate), else a
 *    heartbeat at ≤1 Hz (0.2 Hz while stationary). Frames are full state, so a
 *    dropped stream frame self-heals on the next.
 *  - **Terminal** (arrived/cancelled) — reliable write, then the session ends.
 *  - **Reconnect** while a session is active — re-send the cached RouteSummary
 *    (the board lost its state), then resume the stream.
 *
 * All timing uses monotonic [SystemClock.elapsedRealtime]. This class holds no
 * routing-SDK types — those live behind [NavigationProvider].
 */
class RouteNavigator(
    private val provider: NavigationProvider,
    private val transport: BluetoothDataSource,
    private val scope: CoroutineScope,
    /** Monotonic clock for send throttling; injectable for deterministic tests. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var sessionJob: Job? = null
    private var seq = 0

    // Per-session send-state (guarded by single-collector confinement).
    private var active = false
    private var lastSentRouteId = -1L
    private var lastSummary: NavProgress? = null
    private var lastManeuverKey: Int? = null
    private var lastState: NavState? = null
    private var lastSendMs = 0L

    // Latest route geometry (from the provider's activeRoute), for sending RouteChunks.
    @Volatile private var currentPlan: RoutePlan? = null

    init {
        // Re-seed the board's route context after a reconnect mid-session.
        transport.connectionState
            .onEach { if (it == ConnectionState.CONNECTED && active) resendSummary() }
            .launchIn(scope)
    }

    /** Start navigating to [destination]; begins the frame stream. */
    fun startNavigation(destination: GeoLocation) {
        stopInternal(sendCancel = false)
        active = true
        resetSessionState()
        sessionJob = scope.launch {
            // Subscribe concurrently with start(): some providers (the simulator, and
            // any SDK that drives progress from within start()) emit before start()
            // returns, so collecting *after* it would miss everything but the last
            // replayed value. The provider's SharedFlow replay covers the subscribe race.
            launch { provider.activeRoute.collect { currentPlan = it } }
            launch { provider.progress.collect { onProgress(it) } }
            provider.start(destination)
        }
    }

    /** Stop navigating; tells the board to clear the nav UI. */
    fun cancelNavigation() {
        if (!active) return
        val last = lastSummary
        scope.launch {
            transport.writeNav(
                NavigationSchema.frameNavInstruction(
                    instruction(
                        NavProgress(
                            routeId = last?.routeId ?: 0L,
                            state = NavState.Cancelled,
                            maneuver = com.example.displayapp.domain.model.Maneuver.None,
                        ),
                    ),
                ),
                reliable = true,
            )
        }
        stopInternal(sendCancel = false)
    }

    private suspend fun onProgress(p: NavProgress) {
        // New route (initial or reroute) → send the route (summary + geometry) first, reliably.
        if (p.routeId != lastSentRouteId && p.state == NavState.Navigating) {
            lastSummary = p
            sendRoute(p)
            lastSentRouteId = p.routeId
        }

        val maneuverKey = maneuverKey(p)
        val maneuverChanged = maneuverKey != lastManeuverKey
        val stateChanged = p.state != lastState
        val heartbeatDue = now() - lastSendMs >= sendIntervalMs(p)

        if (maneuverChanged || stateChanged || heartbeatDue) {
            transport.writeNav(
                NavigationSchema.frameNavInstruction(instruction(p, seq++)),
                reliable = p.state.isTerminal,
            )
            lastManeuverKey = maneuverKey
            lastState = p.state
            lastSendMs = now()
        }

        if (p.state.isTerminal) stopInternal(sendCancel = false)
    }

    private suspend fun resendSummary() {
        val s = lastSummary ?: return
        Timber.d("Nav: reconnect — re-seeding route for ${s.routeId}")
        sendRoute(s)
        lastManeuverKey = null // force the next instruction to send immediately
    }

    /**
     * Send the whole route to the controller: one [NavigationSchema.RouteSummary]
     * (with the real chunk count) followed by the downsampled route geometry as
     * [NavigationSchema.RouteChunk] frames — all reliable. Provider-agnostic: the
     * geometry is [RoutePlan.polyline] (domain points) from the active session.
     */
    private suspend fun sendRoute(p: NavProgress) {
        val chunks = currentPlan?.polyline?.let { routeChunks(p.routeId, it) }.orEmpty()
        transport.writeNav(NavigationSchema.frameRouteSummary(summary(p, chunks.size)), reliable = true)
        for (chunk in chunks) transport.writeNav(NavigationSchema.frameRouteChunk(chunk), reliable = true)
    }

    /** Downsample + delta-encode the route line into frame-sized, self-contained chunks. */
    private fun routeChunks(routeId: Long, polyline: List<GeoLocation>): List<NavigationSchema.RouteChunk> {
        val pts = downsample(polyline, MAX_ROUTE_POINTS)
        if (pts.size < 2) return emptyList()
        // Windows overlap by one point so consecutive chunks share a vertex (no gap when
        // the board concatenates them by index).
        val windows = ArrayList<List<GeoLocation>>()
        var start = 0
        while (start < pts.size - 1) {
            val end = minOf(start + POINTS_PER_CHUNK, pts.size)
            windows.add(pts.subList(start, end))
            start = end - 1
        }
        val total = windows.size
        return windows.mapIndexed { index, w ->
            val anchor = w.first()
            val deltas = ShortArray((w.size - 1) * 2)
            for (j in 1 until w.size) {
                deltas[(j - 1) * 2] = deltaE5(w[j].latitude - w[j - 1].latitude)
                deltas[(j - 1) * 2 + 1] = deltaE5(w[j].longitude - w[j - 1].longitude)
            }
            NavigationSchema.RouteChunk(
                routeId = routeId,
                anchorLatE7 = (anchor.latitude * 1e7).roundToInt(),
                anchorLonE7 = (anchor.longitude * 1e7).roundToInt(),
                index = index,
                total = total,
                deltas = deltas,
            )
        }
    }

    private fun deltaE5(degrees: Double): Short =
        (degrees * 1e5).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun downsample(pts: List<GeoLocation>, max: Int): List<GeoLocation> {
        if (pts.size <= max) return pts
        val step = pts.size.toDouble() / max
        val out = ArrayList<GeoLocation>(max + 1)
        var i = 0.0
        while (i < pts.size) { out.add(pts[i.toInt()]); i += step }
        if (out.last() !== pts.last()) out.add(pts.last())
        return out
    }

    private fun stopInternal(sendCancel: Boolean) {
        active = false
        sessionJob?.cancel()
        sessionJob = null
        provider.stop()
        if (sendCancel) cancelNavigation()
    }

    private fun resetSessionState() {
        seq = 0
        lastSentRouteId = -1L
        lastSummary = null
        lastManeuverKey = null
        lastState = null
        lastSendMs = 0L
        currentPlan = null
    }

    /** Send cadence: immediate on non-navigating states, 0.2 Hz stationary, else 1 Hz. */
    private fun sendIntervalMs(p: NavProgress): Long = when {
        p.state != NavState.Navigating -> 0L
        p.speedKmh < 2 -> 5_000L
        else -> 1_000L
    }

    private fun maneuverKey(p: NavProgress): Int =
        (p.routeId.toInt() * 31) xor (p.maneuver.wire shl 8) xor p.roundaboutExit

    private fun summary(p: NavProgress, polylineChunks: Int) = NavigationSchema.RouteSummary(
        routeId = p.routeId,
        totalDistanceM = u32(p.totalDistanceM),
        totalDurationSec = u32(p.totalDurationSec),
        maneuverCount = u16(p.maneuverCount),
        polylineChunks = polylineChunks,
        schemaVersion = SCHEMA_VERSION,
        destinationName = p.destinationName,
    )

    private fun instruction(p: NavProgress, seqValue: Int = seq) = NavigationSchema.NavInstruction(
        routeId = p.routeId,
        distanceRemainingM = u32(p.distanceRemainingM),
        etaSeconds = u32(p.etaSeconds),
        seq = u16(seqValue),
        state = p.state.wire,
        maneuver = p.maneuver.wire,
        distanceToTurnM = u16(p.distanceToTurnM),
        nextManeuver = p.nextManeuver.wire,
        nextDistanceM = u16(p.nextDistanceM),
        roundaboutExit = u8(p.roundaboutExit),
        speedLimitKmh = u8(p.speedLimitKmh),
        streetName = p.streetName,
    )

    private fun u8(v: Int) = v.coerceIn(0, 0xFF)
    private fun u16(v: Int) = v.coerceIn(0, 0xFFFF)
    private fun u32(v: Int) = v.coerceAtLeast(0).toLong()

    private companion object {
        const val SCHEMA_VERSION = 1
        // Route-geometry sending: cap the drawn line's resolution and keep each chunk
        // well under the 240-byte single-frame limit (≤40 pts ⇒ ~197 B payload).
        const val MAX_ROUTE_POINTS = 200
        const val POINTS_PER_CHUNK = 40
    }
}
