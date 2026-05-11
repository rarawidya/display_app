package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable

@Immutable
data class ChartsUiState(
    val timestamps: List<Long> = emptyList(),
    val speed:       List<Float> = emptyList(),
    val voltage:     List<Float> = emptyList(),
    val current:     List<Float> = emptyList(),
    val temperature: List<Float> = emptyList(),
    val battery:     List<Float> = emptyList(),
    val selectedMetrics: Set<TelemetryMetric> = setOf(TelemetryMetric.Speed, TelemetryMetric.Battery),
    val focusedMetric: TelemetryMetric = TelemetryMetric.Speed,
    val rangeSec: Int = TimeRange.SEC_60.seconds
) {
    fun valuesFor(metric: TelemetryMetric): List<Float> = when (metric) {
        TelemetryMetric.Speed       -> speed
        TelemetryMetric.Voltage     -> voltage
        TelemetryMetric.Current     -> current
        TelemetryMetric.Temperature -> temperature
        TelemetryMetric.Battery     -> battery
    }

    fun latestFor(metric: TelemetryMetric): Float? = valuesFor(metric).lastOrNull()
}

enum class TimeRange(val seconds: Int, val label: String) {
    SEC_30(30, "30s"),
    SEC_60(60, "1m"),
    SEC_300(300, "5m"),
    SEC_900(900, "15m")
}
