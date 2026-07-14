package com.innodrive.evdash.presentation.state

import androidx.compose.runtime.Immutable
import com.innodrive.evdash.domain.model.VehicleMode

/**
 * Charts tab UI state. Series are keyed by [TelemetryMetric] so the chart
 * supports the full canonical vocabulary without any per-metric branching
 * here — adding a new metric to [TelemetryMetric] surfaces automatically.
 *
 * Per-frame channels (Speed, Voltage, …) are pushed every sample. Rolling
 * channels (Wh/km, range) push `Float.NaN` when their underlying value is
 * null, and the chart skips NaN segments when drawing.
 *
 * [vehicleMode] mirrors the live cockpit mode so the Charts header can show
 * the same drive-mode badge the Drive page is showing.
 */
@Immutable
data class ChartsUiState(
    val timestamps: List<Long> = emptyList(),
    val series: Map<TelemetryMetric, List<Float>> = emptyMap(),
    val selectedMetrics: Set<TelemetryMetric> = setOf(TelemetryMetric.Speed, TelemetryMetric.Battery),
    val focusedMetric: TelemetryMetric = TelemetryMetric.Speed,
    val rangeSec: Int = TimeRange.SEC_60.seconds,
    val vehicleMode: VehicleMode = VehicleMode.PARK
) {
    fun valuesFor(metric: TelemetryMetric): List<Float> =
        series[metric] ?: emptyList()

    fun latestFor(metric: TelemetryMetric): Float? =
        valuesFor(metric).lastOrNull { !it.isNaN() }
}

enum class TimeRange(val seconds: Int, val label: String) {
    SEC_30(30, "30s"),
    SEC_60(60, "1m"),
    SEC_300(300, "5m"),
    SEC_900(900, "15m")
}
