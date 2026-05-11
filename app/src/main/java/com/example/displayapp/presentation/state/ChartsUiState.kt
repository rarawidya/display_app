package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable

/**
 * Snapshot of the rolling telemetry buffer for the Charts tab.
 *
 * Each list is an aligned time-series — index `i` of every list shares
 * the same timestamp `timestamps[i]`. Index 0 is the oldest sample.
 *
 * @Immutable so Compose can skip recomposition on identical references.
 * Lists are immutable (List<Float>); the VM swaps the whole state on each tick.
 */
@Immutable
data class ChartsUiState(
    val timestamps: List<Long> = emptyList(),
    val speed:       List<Float> = emptyList(),
    val voltage:     List<Float> = emptyList(),
    val current:     List<Float> = emptyList(),
    val temperature: List<Float> = emptyList(),
    val battery:     List<Float> = emptyList(),
    val rangeSec: Int = TimeRange.SEC_60.seconds
)

enum class TimeRange(val seconds: Int, val label: String) {
    SEC_30(30, "30s"),
    SEC_60(60, "1m"),
    SEC_300(300, "5m"),
    SEC_900(900, "15m")
}
