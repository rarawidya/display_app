package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.data.persistence.entity.TripEntity
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * UI-friendly trip row.
 * The VM precomputes formatted strings so the composable doesn't allocate per recomposition.
 */
@Immutable
data class TripRow(
    val id: Long,
    val startMs: Long,
    val durationLabel: String,
    val distanceLabel: String,
    val avgSpeedLabel: String,
    val maxSpeedLabel: String,
    val batteryLabel: String,
    val energyLabel: String,
    val isActive: Boolean,
    // Raw values retained for filter/sort math — avoids re-parsing label strings.
    val distanceMeters: Long,
    val durationSec: Long,
    val maxSpeedKmh10: Int,
    val avgSpeedKmh10: Int,
    val energyKwh: Float
)

/** Date-range filter chip values. */
enum class DateRange(val label: String) {
    TODAY("Today"),
    DAYS_7("7d"),
    DAYS_30("30d"),
    ALL("All")
}

/** Sort order for the trip list. */
enum class SortBy(val label: String) {
    NEWEST("Newest"),
    LONGEST("Longest"),
    FASTEST("Fastest")
}

@Immutable
data class LogsUiState(
    val trips: List<TripRow> = emptyList(),
    val query: String = "",
    val filter: TripFilter = TripFilter.ALL,
    val dateRange: DateRange = DateRange.ALL,
    val sortBy: SortBy = SortBy.NEWEST,
    val pendingDeleteIds: Set<Long> = emptySet(),

    // Export feedback
    val isExporting: Boolean = false,
    val lastExportPath: String? = null,
    val lastExportError: String? = null,

    // Undo feedback
    val pendingUndoTripId: Long? = null
) {
    /**
     * Trips visible after all filters and sorting are applied.
     *
     * Filter order (in/out is order-sensitive for performance — cheapest filters first):
     *   1. Hide pending deletes (set membership = O(1))
     *   2. Date range
     *   3. Status filter
     *   4. Distance filter (>5km)
     *   5. Full-text query
     *   6. Sort
     */
    val visibleTrips: List<TripRow>
        get() {
            val now = System.currentTimeMillis()
            val rangeCutoff = when (dateRange) {
                DateRange.TODAY  -> startOfTodayMs(now)
                DateRange.DAYS_7 -> now - 7L * DAY_MS
                DateRange.DAYS_30 -> now - 30L * DAY_MS
                DateRange.ALL    -> Long.MIN_VALUE
            }
            val filtered = trips.asSequence()
                .filterNot { it.id in pendingDeleteIds }
                .filter { it.startMs >= rangeCutoff }
                .let { seq ->
                    when (filter) {
                        TripFilter.ALL    -> seq
                        TripFilter.ACTIVE -> seq.filter { it.isActive }
                        TripFilter.LONG   -> seq.filter { it.distanceMeters > 5_000 }
                    }
                }
                .let { seq ->
                    if (query.isBlank()) seq
                    else {
                        val q = query.trim()
                        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
                        seq.filter { row ->
                            row.distanceLabel.contains(q, ignoreCase = true) ||
                                row.durationLabel.contains(q, ignoreCase = true) ||
                                dateFmt.format(java.util.Date(row.startMs)).contains(q, ignoreCase = true)
                        }
                    }
                }
            val sorted = when (sortBy) {
                SortBy.NEWEST  -> filtered.sortedByDescending { it.startMs }
                SortBy.LONGEST -> filtered.sortedByDescending { it.distanceMeters }
                SortBy.FASTEST -> filtered.sortedByDescending { it.maxSpeedKmh10 }
            }
            return sorted.toList()
        }

    /**
     * Aggregates over the *visible* trips — drives the summary band. Returned
     * in raw units; the UI formats with [com.example.displayapp.presentation.ui.common.LocalAppSettings]
     * so unit toggles take effect without re-collecting the flow.
     */
    val summary: LogsSummary
        get() {
            val list = visibleTrips
            if (list.isEmpty()) return LogsSummary()
            return LogsSummary(
                tripCount = list.size,
                totalDistanceMeters = list.sumOf { it.distanceMeters },
                totalEnergyKwh = list.sumOf { it.energyKwh.toDouble() }.toFloat(),
                avgSpeedKmh10 = list.sumOf { it.avgSpeedKmh10.toLong() } / list.size
            )
        }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun startOfTodayMs(now: Long): Long {
            // Naive day-start using local timezone via System.default — good enough
            // for filter cutoffs; doesn't need exact local-midnight precision.
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}

@Immutable
data class LogsSummary(
    val tripCount: Int = 0,
    val totalDistanceMeters: Long = 0L,
    val totalEnergyKwh: Float = 0f,
    /** Average speed in km/h × 10 (matches TripEntity's units). */
    val avgSpeedKmh10: Long = 0L
)

enum class TripFilter(val label: String) { ALL("All"), ACTIVE("Active"), LONG(">5km") }

/** Map a TripEntity → TripRow with all display formatting done up-front. */
fun TripEntity.toRow(): TripRow {
    val durationSec = ((endTime ?: System.currentTimeMillis()) - startTime) / 1000
    val distKm = distanceMeters / 1000f
    val maxSpd = maxSpeedKmh10 / 10f
    val avgSpd = avgSpeedKmh10 / 10f
    val end = endBattery ?: startBattery
    val deltaBatt = (startBattery - end).coerceAtLeast(0)
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
        isActive = endTime == null,
        distanceMeters = distanceMeters,
        durationSec = durationSec,
        maxSpeedKmh10 = maxSpeedKmh10,
        avgSpeedKmh10 = avgSpeedKmh10,
        energyKwh = energyKwh
    )
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}
