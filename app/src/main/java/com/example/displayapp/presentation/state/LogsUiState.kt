package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.data.persistence.entity.TripEntity

/**
 * UI-friendly trip row.
 * The VM precomputes formatted strings so the composable doesn't allocate per recomposition.
 */
@Immutable
data class TripRow(
    val id: Long,
    val startMs: Long,
    val durationLabel: String,    // "12m 34s" or "1h 02m"
    val distanceLabel: String,    // "8.4 km"
    val avgSpeedLabel: String,    // "32 km/h"
    val maxSpeedLabel: String,    // "61 km/h"
    val batteryLabel: String,     // "82% → 47%"
    val energyLabel: String,      // "≈ 1.2 kWh" — heuristic
    val isActive: Boolean         // true if endTime is null
)

@Immutable
data class LogsUiState(
    val trips: List<TripRow> = emptyList(),
    val query: String = "",
    val filter: TripFilter = TripFilter.ALL,
    val isExporting: Boolean = false,
    val lastExportPath: String? = null,
    val lastExportError: String? = null
) {
    val visibleTrips: List<TripRow>
        get() = trips
            .let { if (filter == TripFilter.ACTIVE) it.filter { t -> t.isActive } else it }
            .let { if (filter == TripFilter.LONG)   it.filter { t -> t.distanceMeters() > 5_000 } else it }
            .let { q ->
                if (query.isBlank()) q
                else q.filter { it.distanceLabel.contains(query, ignoreCase = true) ||
                    it.durationLabel.contains(query, ignoreCase = true) ||
                    it.startMs.toString().contains(query) }
            }
}

enum class TripFilter(val label: String) { ALL("All"), ACTIVE("Active"), LONG(">5km") }

/**
 * Heuristic distance pull from "8.4 km" formatting → meters.
 * Cheaper than carrying a separate raw-meters field for filter purposes.
 */
private fun TripRow.distanceMeters(): Long {
    val km = distanceLabel.removeSuffix(" km").toFloatOrNull() ?: 0f
    return (km * 1000).toLong()
}

/** Map a TripEntity → TripRow with all display formatting done up-front. */
fun TripEntity.toRow(): TripRow {
    val durationSec = ((endTime ?: System.currentTimeMillis()) - startTime) / 1000
    val distKm = distanceMeters / 1000f
    val maxSpd = maxSpeedKmh10 / 10f
    val avgSpd = avgSpeedKmh10 / 10f
    val end = endBattery ?: startBattery
    val deltaBatt = (startBattery - end).coerceAtLeast(0)
    // Heuristic energy estimate: assume ~0.6 kWh / 10% on a typical small EV.
    val energyKwh = deltaBatt / 10f * 0.6f

    return TripRow(
        id = id,
        startMs = startTime,
        durationLabel = formatDuration(durationSec),
        distanceLabel = "%.1f km".format(distKm),
        avgSpeedLabel = "%.0f km/h".format(avgSpd),
        maxSpeedLabel = "%.0f km/h".format(maxSpd),
        batteryLabel = "$startBattery% → ${endBattery ?: '—'}%",
        energyLabel = if (energyKwh > 0f) "≈ %.1f kWh".format(energyKwh) else "—",
        isActive = endTime == null
    )
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}
