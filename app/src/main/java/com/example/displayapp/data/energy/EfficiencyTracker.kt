package com.example.displayapp.data.energy

import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Live efficiency state for the Drive page. Independent from
 * [com.example.displayapp.data.persistence.TripSessionManager]'s accumulator,
 * which tracks lifetime trip energy — this tracker is a *rolling* view used
 * for the realtime Wh/km tile and range estimate.
 *
 * Algorithm (per-sample):
 *  1. Append (V, I, speed, odometer, battery, t) to a deque.
 *  2. Trim from the front until window spans no more than [windowMs].
 *  3. Trapezoidal integrate V × I × dt over the window → energyWh.
 *  4. Distance from odometer delta over the window → distanceKm.
 *  5. If distance ≥ [MIN_DISTANCE_KM]: raw Wh/km = energyWh / distanceKm.
 *     Otherwise raw = null (idle / standing still — division would blow up).
 *  6. EWMA-smooth the raw value with α = [EWMA_ALPHA] so the displayed
 *     number doesn't jitter at 20 Hz.
 *  7. Range = batteryPercent × packCapacityKwh × 10 / smoothedWhPerKm.
 *
 * Determinism: no clock reads — all timing comes from the sample timestamps
 * provided by the telemetry pipeline. Unit-testable by feeding synthetic
 * VehicleData with controlled timestamps.
 *
 * Reset: when ConnectionState transitions to DISCONNECTED, the window and
 * EWMA state are cleared so the next session starts fresh.
 *
 * @param packCapacityKwh Pack capacity in kWh. Defaults to the GESITS G-1
 *   test vehicle's pack; Phase 3 makes this user-editable via AppSettings.
 */
class EfficiencyTracker(
    vehicleData: StateFlow<VehicleData>,
    connectionState: StateFlow<ConnectionState>,
    private val packCapacityKwh: Float = DEFAULT_PACK_CAPACITY_KWH,
    private val windowMs: Long = WINDOW_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    /**
     * Snapshot of derived metrics consumed by the Dashboard.
     *
     * @param whPerKm null when there's not enough distance in the window yet
     *   (standing still, just connected, or driving < 10 m so far).
     * @param rangeKm null when whPerKm is null or battery is unknown.
     * @param instantPowerW current discharge power (0 if regenerating).
     * @param regenPowerW current regen power (0 if discharging).
     */
    data class State(
        val whPerKm: Float? = null,
        val rangeKm: Float? = null,
        val instantPowerW: Float = 0f,
        val regenPowerW: Float = 0f
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val window = ArrayDeque<Sample>()
    private var smoothedWhPerKm: Float? = null

    init {
        // One coroutine merging both flows — collect() will emit on every
        // vehicleData tick (~20 Hz). connectionState changes are sparse so
        // combine's coalescing behavior won't drop samples.
        scope.launch {
            combine(vehicleData, connectionState) { data, conn -> data to conn }
                .collect { (data, conn) ->
                    when (conn) {
                        ConnectionState.DISCONNECTED -> reset()
                        ConnectionState.CONNECTED, ConnectionState.RECONNECTING ->
                            ingest(data)
                        else -> Unit  // SCANNING / CONNECTING — ignore samples
                    }
                }
        }
    }

    private fun ingest(data: VehicleData) {
        if (data.timestamp <= 0L) return

        val sample = Sample(
            voltage = data.voltage,
            current = data.current,
            odometerKm = data.odometer,
            batteryPercent = data.batteryPercent,
            timestamp = data.timestamp
        )
        window.addLast(sample)
        // Trim from the front until the window is within the time bound.
        while (window.size >= 2 && (sample.timestamp - window.first().timestamp) > windowMs) {
            window.removeFirst()
        }
        recompute(sample)
    }

    private fun recompute(latest: Sample) {
        val instantPowerW = (latest.voltage * latest.current)
        val instantDischarge = if (instantPowerW > 0f) instantPowerW else 0f
        val instantRegen = if (instantPowerW < 0f) -instantPowerW else 0f

        var rawWhPerKm: Float? = null
        if (window.size >= 2) {
            var energyWh = 0.0
            for (i in 1 until window.size) {
                val prev = window[i - 1]
                val cur = window[i]
                val dtMsRaw = cur.timestamp - prev.timestamp
                if (dtMsRaw <= 0L) continue
                val dtMs = if (dtMsRaw > EnergyAccumulator.MAX_DT_MS) {
                    EnergyAccumulator.MAX_DT_MS
                } else dtMsRaw
                val avgPower = (prev.voltage * prev.current + cur.voltage * cur.current) / 2.0
                energyWh += avgPower * dtMs / 3_600_000.0
            }
            val distanceKm = (window.last().odometerKm - window.first().odometerKm)
                .coerceAtLeast(0f)
            if (distanceKm >= MIN_DISTANCE_KM) {
                rawWhPerKm = (energyWh / distanceKm).toFloat()
            }
        }

        // EWMA — when raw is null we don't update the smoothed value; the
        // previous reading lingers until either fresh distance accumulates or
        // a reset clears it. This avoids visible flicker between "—" and a
        // computed number at low speeds.
        smoothedWhPerKm = when {
            rawWhPerKm == null -> smoothedWhPerKm  // hold last value
            smoothedWhPerKm == null -> rawWhPerKm
            else -> EWMA_ALPHA * rawWhPerKm + (1f - EWMA_ALPHA) * smoothedWhPerKm!!
        }

        val rangeKm: Float? = computeRangeKm(latest.batteryPercent, smoothedWhPerKm)

        _state.value = State(
            whPerKm = smoothedWhPerKm,
            rangeKm = rangeKm,
            instantPowerW = instantDischarge,
            regenPowerW = instantRegen
        )
    }

    /**
     * battery% × packCapKwh × 1000 / Wh/km
     * = battery% × packCapKwh × 10 / Wh/km
     * = km of range remaining at the current rolling efficiency.
     *
     * Returns null when efficiency is unknown, non-positive (regen-dominant),
     * or battery is zero/unknown.
     */
    private fun computeRangeKm(batteryPercent: Int, whPerKm: Float?): Float? {
        if (whPerKm == null || whPerKm <= 0f || batteryPercent <= 0) return null
        return batteryPercent * packCapacityKwh * 10f / whPerKm
    }

    private fun reset() {
        if (window.isEmpty() && smoothedWhPerKm == null && _state.value == State()) return
        window.clear()
        smoothedWhPerKm = null
        _state.value = State()
    }

    private data class Sample(
        val voltage: Float,
        val current: Float,
        val odometerKm: Float,
        val batteryPercent: Int,
        val timestamp: Long
    )

    companion object {
        /** Rolling window of the most recent 60 seconds of telemetry. */
        const val WINDOW_MS = 60_000L

        /** Don't compute Wh/km until the vehicle has covered at least 10 m
         *  within the window — prevents instability at near-zero speed. */
        const val MIN_DISTANCE_KM = 0.010f

        /** EWMA smoothing factor. Lower = smoother but slower to follow real
         *  changes. 0.15 follows acceleration/deceleration within ~5 s. */
        const val EWMA_ALPHA = 0.15f

        /** GESITS G-1 pack — typical test vehicle. Phase 3 moves to AppSettings. */
        const val DEFAULT_PACK_CAPACITY_KWH = 1.7f
    }
}
