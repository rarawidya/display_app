package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.repository.VehicleRepository
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TimeRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * ViewModel for the Charts tab.
 *
 * Maintains a fixed-capacity ring buffer of the most recent telemetry samples.
 * The Charts UI consumes [uiState] and renders all series from the same snapshot
 * — every tile shows the same time window even if one series momentarily skips
 * a sample.
 *
 * Why ArrayDeque + manual cap?
 * - O(1) push/pop at both ends
 * - Bounded memory (no leaks)
 * - One buffer per series keeps the producer single-threaded (the VM scope)
 *
 * Throttling:
 * - We accept every sample upstream emits but only emit a UI snapshot every
 *   ~100 ms (10 fps). Charts don't need 20 fps and 10 fps is plenty visually.
 *   Cuts recompositions roughly in half during heavy telemetry.
 */
class ChartsViewModel(
    private val repository: VehicleRepository
) : ViewModel() {

    /** Hard cap covering the largest selectable time window at the source rate. */
    private val capacity = TimeRange.SEC_900.seconds * SOURCE_HZ

    private val timestamps = ArrayDeque<Long>(capacity)
    private val speed       = ArrayDeque<Float>(capacity)
    private val voltage     = ArrayDeque<Float>(capacity)
    private val current     = ArrayDeque<Float>(capacity)
    private val temperature = ArrayDeque<Float>(capacity)
    private val battery     = ArrayDeque<Float>(capacity)

    private val _uiState = MutableStateFlow(ChartsUiState(rangeSec = TimeRange.SEC_60.seconds))
    val uiState: StateFlow<ChartsUiState> = _uiState.asStateFlow()

    private var lastEmitMs = 0L

    init {
        viewModelScope.launch {
            repository.vehicleData.collect { sample -> ingest(sample) }
        }
    }

    fun setRange(range: TimeRange) {
        _uiState.update { it.copy(rangeSec = range.seconds) }
        emitSnapshot(force = true)
    }

    private fun ingest(sample: VehicleData) {
        push(timestamps, sample.timestamp)
        push(speed,       sample.speed.toFloat())
        push(voltage,     sample.voltage)
        push(current,     sample.current)
        push(temperature, sample.temperature.toFloat())
        push(battery,     sample.batteryPercent.toFloat())

        // Drop anything older than the current window so memory stays bounded
        val now = sample.timestamp
        val windowStart = now - _uiState.value.rangeSec * 1000L
        while (timestamps.isNotEmpty()) {
            val oldest = timestamps.peekFirst() ?: break
            if (oldest >= windowStart) break
            timestamps.pollFirst()
            speed.pollFirst(); voltage.pollFirst(); current.pollFirst()
            temperature.pollFirst(); battery.pollFirst()
        }

        emitSnapshot(force = false)
    }

    private fun emitSnapshot(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitMs < EMIT_INTERVAL_MS) return
        lastEmitMs = now
        _uiState.update {
            it.copy(
                timestamps   = timestamps.toList(),
                speed        = speed.toList(),
                voltage      = voltage.toList(),
                current      = current.toList(),
                temperature  = temperature.toList(),
                battery      = battery.toList()
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
