package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.repository.VehicleRepository
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.LiveTelemetry
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.presentation.state.TimeRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * ViewModel for the Charts tab.
 *
 * Subscribes to the canonical [LiveTelemetry] stream (vehicle frames merged
 * with the rolling [EfficiencyTracker] state) and maintains one fixed-cap
 * ring buffer per [TelemetryMetric]. Adding a metric to the enum threads it
 * through here automatically — no per-metric branching.
 *
 * Why ArrayDeque + manual cap?
 * - O(1) push/pop at both ends
 * - Bounded memory (no leaks)
 * - Single-threaded producer (VM scope) keeps state machine simple
 *
 * Throttling:
 * - Every upstream sample is ingested. The UI snapshot is published at
 *   most every [EMIT_INTERVAL_MS] (10 fps) — plenty for line charts and
 *   roughly half the recompositions of the raw 20 Hz rate.
 *
 * Rolling channels (Wh/km, range) push `Float.NaN` when their underlying
 * value is null; the chart renderer skips NaN samples.
 */
class ChartsViewModel(
    private val repository: VehicleRepository,
    private val efficiencyTracker: EfficiencyTracker
) : ViewModel() {

    /** Hard cap covering the largest selectable time window at the source rate. */
    private val capacity = TimeRange.SEC_900.seconds * SOURCE_HZ

    private val timestamps = ArrayDeque<Long>(capacity)
    private val series: Map<TelemetryMetric, ArrayDeque<Float>> =
        TelemetryMetric.entries.associateWith { ArrayDeque<Float>(capacity) }

    private val _uiState = MutableStateFlow(ChartsUiState(rangeSec = TimeRange.SEC_60.seconds))
    val uiState: StateFlow<ChartsUiState> = _uiState.asStateFlow()

    private var lastEmitMs = 0L
    private var lastSnapshot: LiveTelemetry = LiveTelemetry()
    private var lastIngestedFrame: VehicleData? = null

    init {
        viewModelScope.launch {
            combine(
                repository.vehicleData,
                efficiencyTracker.state
            ) { vehicle, efficiency -> LiveTelemetry(vehicle, efficiency) }
                .collect { snapshot -> ingest(snapshot) }
        }
    }

    fun setRange(range: TimeRange) {
        _uiState.update { it.copy(rangeSec = range.seconds) }
        emitSnapshot(force = true)
    }

    fun toggleMetric(metric: TelemetryMetric) {
        _uiState.update { state ->
            val next = if (metric in state.selectedMetrics)
                state.selectedMetrics - metric
            else
                state.selectedMetrics + metric

            val nextFocus = when {
                state.focusedMetric in next -> state.focusedMetric
                next.isEmpty() -> state.focusedMetric
                else -> next.first()
            }
            state.copy(selectedMetrics = next, focusedMetric = nextFocus)
        }
    }

    fun focusMetric(metric: TelemetryMetric) {
        _uiState.update { state ->
            state.copy(
                focusedMetric = metric,
                selectedMetrics = state.selectedMetrics + metric
            )
        }
    }

    private fun ingest(live: LiveTelemetry) {
        val ts = live.vehicle.timestamp.takeIf { it > 0L } ?: return
        lastSnapshot = live
        // combine(vehicle, efficiency) re-emits on efficiency-only updates that
        // reuse the same vehicle frame. Don't push a duplicate point (it would
        // double the sample count and fill the ring buffer at 2× rate); just
        // refresh the snapshot so rolling values still surface.
        if (live.vehicle === lastIngestedFrame) {
            emitSnapshot(force = false)
            return
        }
        lastIngestedFrame = live.vehicle
        push(timestamps, ts)
        for (metric in TelemetryMetric.entries) {
            val value = metric.valueFrom(live) ?: Float.NaN
            push(series.getValue(metric), value)
        }

        // Drop anything older than the current window so memory stays bounded.
        val windowStart = ts - _uiState.value.rangeSec * 1000L
        while (timestamps.isNotEmpty()) {
            val oldest = timestamps.peekFirst() ?: break
            if (oldest >= windowStart) break
            timestamps.pollFirst()
            series.values.forEach { it.pollFirst() }
        }

        emitSnapshot(force = false)
    }

    private fun emitSnapshot(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitMs < EMIT_INTERVAL_MS) return
        lastEmitMs = now
        _uiState.update { state ->
            state.copy(
                timestamps = timestamps.toList(),
                series = series.mapValues { (_, deque) -> deque.toList() },
                vehicleMode = lastSnapshot.vehicle.vehicleMode
            )
        }
    }

    private fun <T> push(deque: ArrayDeque<T>, value: T) {
        deque.addLast(value)
        while (deque.size > capacity) deque.pollFirst()
    }

    companion object {
        /** Approximate upstream sample rate from the protocol. */
        const val SOURCE_HZ = 20

        /** Min interval between UI snapshots. 10 fps is plenty for line charts. */
        const val EMIT_INTERVAL_MS = 100L
    }
}
