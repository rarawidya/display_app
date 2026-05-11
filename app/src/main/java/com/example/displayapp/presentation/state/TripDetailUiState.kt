package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable

/**
 * Pre-formatted state for the Trip Detail screen.
 *
 * All formatting + downsampling happens in the ViewModel, so the Composable
 * just renders. Holding the chart series as `FloatArray` instead of
 * `List<Float>` keeps allocations down and the chart's draw loop tight.
 */
@Immutable
data class TripDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val tripId: Long = -1L,

    // Hero summary
    val dateLabel: String = "",
    val durationLabel: String = "",
    val distanceLabel: String = "",
    val avgSpeedLabel: String = "",
    val maxSpeedLabel: String = "",
    val batteryDeltaLabel: String = "",
    val energyLabel: String = "",
    val sampleCount: Long = 0L,
    val isActive: Boolean = false,

    // Chart series (downsampled, float arrays for cheap draw loops)
    val speedSeries: FloatArray = FloatArray(0),
    val voltageSeries: FloatArray = FloatArray(0),
    val currentSeries: FloatArray = FloatArray(0),
    val temperatureSeries: FloatArray = FloatArray(0),

    // Export feedback
    val exportInProgress: Boolean = false,
    val exportedFilePath: String? = null,
    val exportError: String? = null
) {
    /**
     * Equals/hashCode for FloatArray fields — data class default uses reference
     * equality for arrays, which would break Compose's `@Stable` skipping. We
     * compare by sample count which is a good-enough proxy: a re-load of the
     * same trip produces the same length, and any DB mutation changes it.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TripDetailUiState) return false
        return loading == other.loading &&
            notFound == other.notFound &&
            tripId == other.tripId &&
            dateLabel == other.dateLabel &&
            durationLabel == other.durationLabel &&
            distanceLabel == other.distanceLabel &&
            avgSpeedLabel == other.avgSpeedLabel &&
            maxSpeedLabel == other.maxSpeedLabel &&
            batteryDeltaLabel == other.batteryDeltaLabel &&
            energyLabel == other.energyLabel &&
            sampleCount == other.sampleCount &&
            isActive == other.isActive &&
            speedSeries.size == other.speedSeries.size &&
            voltageSeries.size == other.voltageSeries.size &&
            currentSeries.size == other.currentSeries.size &&
            temperatureSeries.size == other.temperatureSeries.size &&
            exportInProgress == other.exportInProgress &&
            exportedFilePath == other.exportedFilePath &&
            exportError == other.exportError
    }

    override fun hashCode(): Int {
        var h = loading.hashCode()
        h = 31 * h + notFound.hashCode()
        h = 31 * h + tripId.hashCode()
        h = 31 * h + dateLabel.hashCode()
        h = 31 * h + durationLabel.hashCode()
        h = 31 * h + distanceLabel.hashCode()
        h = 31 * h + avgSpeedLabel.hashCode()
        h = 31 * h + maxSpeedLabel.hashCode()
        h = 31 * h + batteryDeltaLabel.hashCode()
        h = 31 * h + energyLabel.hashCode()
        h = 31 * h + sampleCount.hashCode()
        h = 31 * h + isActive.hashCode()
        h = 31 * h + speedSeries.size
        h = 31 * h + voltageSeries.size
        h = 31 * h + currentSeries.size
        h = 31 * h + temperatureSeries.size
        h = 31 * h + exportInProgress.hashCode()
        h = 31 * h + (exportedFilePath?.hashCode() ?: 0)
        h = 31 * h + (exportError?.hashCode() ?: 0)
        return h
    }
}
