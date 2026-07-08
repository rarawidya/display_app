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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *  - **Reconnect** while a session is active — re-send the cached RouteSummary +
 *    geometry (the board lost its state), then the latest instruction immediately.
 *
 * The heartbeat is **driven by its own ticker**, not by provider emissions: a
 * provider only emits per GPS fix, so a stationary phone (no fixes) would go
 * silent and the board declares NAV OFFLINE after 15 s (its `/var/run/nav_state`
 * mtime is the liveness signal). The ticker re-sends the last snapshot — the
 * board interpolates the countdown between checkpoints from its own speed, so
 * repeating the same values is correct. `seq` increments on every frame sent
 * (the board's stale/out-of-order guard). All sends are serialized by a [Mutex]
 * (ticker + progress collector race otherwise).
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

    // Per-session send-state (all mutations confined to [sendMutex]).
    private val sendMutex = Mutex()
    private var active = false
    private var lastSentRouteId = -1L
    private var lastSummary: NavProgress? = null
    private var lastProgress: NavProgress? = null
    private var lastManeuverKey: Int? = null
    private var lastState: NavState? = null
    private var lastSendMs = 0L

    // Latest route geometry (from the provider's activeRoute), for sending RouteChunks.
    @Volatile private var currentPlan: RoutePlan? = null

    // Board-facing destination label for this session's RouteSummary; the plan/provider
    // rarely knows the human name (a routing API returns geometry, not the picked
    // place), so the UI hands it in at startNavigation().
    private var destinationName: String = ""

    init {
        // Re-seed the board's route context after a reconnect mid-session.
        transport.connectionState
            .onEach { if (it == ConnectionState.CONNECTED && active) resendSummary() }
            .launchIn(scope)
    }

    /**
     * Start navigating to [destination]; begins the frame stream. [destinationName]
     * is the human label for the board's bottom card (RouteSummary) — pass the
     * picked place's name; blank shows "--" on the board. [initialPlan] is the
     * already-planned preview route; passing it means the provider doesn't have to
     * plan again (a second request that can fail and silently cancel the session).
     */
    fun startNavigation(
        destination: GeoLocation,
        destinationName: String = "",
        initialPlan: RoutePlan? = null,
    ) {
        stopInternal(sendCancel = false)
        active = true
        resetSessionState()
        this.destinationName = destinationName
        currentPlan = initialPlan // chunks available even before activeRoute emits
        sessionJob = scope.launch {
            // Subscribe concurrently with start(): some providers (the simulator, and
            // any SDK that drives progress from within start()) emit before start()
            // returns, so collecting *after* it would miss everything but the last
            // replayed value. The provider's SharedFlow replay covers the subscribe race.
            launch { provider.activeRoute.collect { it?.let { plan -> currentPlan = plan } } }
            launch { provider.progress.collect { onProgress(it) } }
            launch { heartbeat() }
            provider.start(destination, initialPlan)
        }
    }

    /** Stop navigating; tells the board to clear the nav UI. */
    fun cancelNavigation() {
        if (!active) return
        val last = lastSummary
        scope.launch {
            sendMutex.withLock {
                sendInstruction(
                    NavProgress(
                        routeId = last?.routeId ?: 0L,
                        state = NavState.Cancelled,
                        maneuver = com.example.displayapp.domain.model.Maneuver.None,
                    ),
                    reliable = true,
                )
            }
        }
        stopInternal(sendCancel = false)
    }

    private suspend fun onProgress(p: NavProgress) {
        sendMutex.withLock {
            // New route (initial or reroute) → send the route (summary + geometry) first, reliably.
            if (p.routeId != lastSentRouteId && p.state == NavState.Navigating) {
                lastSummary = p
                sendRoute(p)
                lastSentRouteId = p.routeId
            }

            lastProgress = p
            val maneuverChanged = maneuverKey(p) != lastManeuverKey
            val stateChanged = p.state != lastState
            val heartbeatDue = now() - lastSendMs >= sendIntervalMs(p)

            if (maneuverChanged || stateChanged || heartbeatDue) {
                sendInstruction(p, reliable = p.state.isTerminal)
            }
        }

        if (p.state.isTerminal) stopInternal(sendCancel = false)
    }

    /**
     * The countdown heartbeat (docs/NAVIGATION-INTEGRATION.md §5): re-send the last
     * snapshot at ≤1 Hz moving / 0.2 Hz stationary even when the provider is silent
     * (no GPS fixes) — the board's liveness window is 5 s (dim) / 15 s (NAV OFFLINE).
     * Lives inside the session job, so it dies with the session.
     */
    private suspend fun heartbeat() {
        while (true) {
            delay(HEARTBEAT_POLL_MS)
            sendMutex.withLock {
                val p = lastProgress ?: return@withLock
                if (p.state.isTerminal) return@withLock
                if (now() - lastSendMs >= heartbeatIntervalMs(p)) {
                    sendInstruction(p, reliable = false)
                }
            }
        }
    }

    /** Encode + write one NavInstruction; bumps `seq` and the shared send-state. */
    private suspend fun sendInstruction(p: NavProgress, reliable: Boolean) {
        transport.writeNav(NavigationSchema.frameNavInstruction(instruction(p, seq)), reliable)
        // Rolling 16-bit counter (the board's stale/order guard handles wraparound);
        // u16() clamping would freeze it after 65535 frames (~18 h at 1 Hz).
        seq = (seq + 1) and 0xFFFF
        lastManeuverKey = maneuverKey(p)
        lastState = p.state
        lastSendMs = now()
    }

    private suspend fun resendSummary() {
        sendMutex.withLock {
            val s = lastSummary ?: return
            Timber.d("Nav: reconnect — re-seeding route for ${s.routeId}")
            sendRoute(s)
            // "…then the next instruction": the board just lost all nav state, so
            // follow the summary with the latest snapshot immediately.
            lastProgress?.let { sendInstruction(it, reliable = false) }
        }
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
        lastProgress = null
        lastManeuverKey = null
        lastState = null
        lastSendMs = 0L
        currentPlan = null
        destinationName = ""
    }

    /** Send cadence: immediate on non-navigating states, 0.2 Hz stationary, else 1 Hz. */
    private fun sendIntervalMs(p: NavProgress): Long = when {
        p.state != NavState.Navigating -> 0L
        p.speedKmh < 2 -> 5_000L
        else -> 1_000L
    }

    /** Ticker cadence (no immediate case — event sends are the collector's job). */
    private fun heartbeatIntervalMs(p: NavProgress): Long =
        if (p.speedKmh < 2) 5_000L else 1_000L

    private fun maneuverKey(p: NavProgress): Int =
        (p.routeId.toInt() * 31) xor (p.maneuver.wire shl 8) xor p.roundaboutExit

    private fun summary(p: NavProgress, polylineChunks: Int) = NavigationSchema.RouteSummary(
        routeId = p.routeId,
        totalDistanceM = u32(p.totalDistanceM),
        totalDurationSec = u32(p.totalDurationSec),
        maneuverCount = u16(p.maneuverCount),
        polylineChunks = polylineChunks,
        schemaVersion = SCHEMA_VERSION,
        // The provider rarely knows the picked place's label; fall back to the
        // session name handed in by the UI so the board's title isn't "--".
        destinationName = p.destinationName.ifBlank { destinationName },
    )

    private fun instruction(p: NavProgress, seqValue: Int) = NavigationSchema.NavInstruction(
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
        /** Ticker granularity; actual send rate is [heartbeatIntervalMs]-gated. */
        const val HEARTBEAT_POLL_MS = 250L
        // Route-geometry sending: cap the drawn line's resolution and keep each chunk
        // well under the 240-byte single-frame limit (≤40 pts ⇒ ~197 B payload).
        const val MAX_ROUTE_POINTS = 200
        const val POINTS_PER_CHUNK = 40
    }
}
