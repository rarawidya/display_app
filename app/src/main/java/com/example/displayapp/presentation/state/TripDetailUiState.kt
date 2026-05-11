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

    // Raw values — UI formats with LocalAppSettings so unit toggles take effect instantly.
    val startMs: Long = 0L,
    val durationSec: Long = 0L,
    val distanceMeters: Long = 0L,
    val avgSpeedKmh10: Int = 0,
    val maxSpeedKmh10: Int = 0,
    val startBattery: Int = 0,
    val endBattery: Int? = null,
    val energyKwh: Float = 0f,

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
            startMs == other.startMs &&
            durationSec == other.durationSec &&
            distanceMeters == other.distanceMeters &&
            avgSpeedKmh10 == other.avgSpeedKmh10 &&
            maxSpeedKmh10 == other.maxSpeedKmh10 &&
            startBattery == other.startBattery &&
            endBattery == other.endBattery &&
            energyKwh == other.energyKwh &&
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
        h = 31 * h + startMs.hashCode()
        h = 31 * h + durationSec.hashCode()
        h = 31 * h + distanceMeters.hashCode()
        h = 31 * h + avgSpeedKmh10
        h = 31 * h + maxSpeedKmh10
        h = 31 * h + startBattery
        h = 31 * h + (endBattery ?: 0)
        h = 31 * h + energyKwh.hashCode()
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
