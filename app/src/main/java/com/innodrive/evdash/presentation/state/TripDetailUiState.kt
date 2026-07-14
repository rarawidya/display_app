package com.innodrive.evdash.presentation.state

import androidx.compose.runtime.Immutable
import com.innodrive.evdash.domain.model.VehicleMode

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

    /**
     * Per-kWh electricity rate (e.g. 0.18f for 18¢/kWh). Null while the user
     * hasn't entered one — EnergySummaryCard hides the cost row when null.
     * Wired into the architecture now so a future Settings input can flip it
     * on without code changes elsewhere.
     */
    val ratePerKwh: Float? = null,


    // Raw values — UI formats with LocalAppSettings so unit toggles take effect instantly.
    val startMs: Long = 0L,
    val durationSec: Long = 0L,
    val distanceMeters: Long = 0L,
    val avgSpeedKmh10: Int = 0,
    val maxSpeedKmh10: Int = 0,
    val startBattery: Int = 0,
    val endBattery: Int? = null,
    /**
     * Real per-trip energy in Wh, integrated from V × I × dt during recording.
     * Pre-v2 trips have `energyUsedWh == 0.0 && energyRegenWh == 0.0` and the
     * UI shows "—" rather than back-filling with a heuristic.
     */
    val energyUsedWh: Double = 0.0,
    val energyRegenWh: Double = 0.0,

    // v5 aggregates — Watts as Float, °C as Int (matches per-sample columns).
    val avgPowerW: Float = 0f,
    val maxPowerW: Float = 0f,
    val peakMotorTempC: Int = 0,
    val peakBatteryTempC: Int = 0,
    val peakControllerTempC: Int = 0,

    /**
     * Most-frequent mode across the trip's persisted samples. Defaults to
     * PARK when the trip has no telemetry (eg. zero-sample stale row).
     */
    val dominantMode: VehicleMode = VehicleMode.PARK,

    val sampleCount: Long = 0L,
    val isActive: Boolean = false,

    /** Fault/warning events recorded during this trip, newest first. */
    val faults: List<TripFaultRow> = emptyList(),

    // Chart series (downsampled, float arrays for cheap draw loops).
    // All units stay SI — the UI converts at the label site via LocalAppSettings.
    val speedSeries: FloatArray = FloatArray(0),
    val rpmSeries: FloatArray = FloatArray(0),
    val voltageSeries: FloatArray = FloatArray(0),
    val currentSeries: FloatArray = FloatArray(0),
    val powerSeries: FloatArray = FloatArray(0),
    val batterySeries: FloatArray = FloatArray(0),
    val temperatureSeries: FloatArray = FloatArray(0),
    val batteryTempSeries: FloatArray = FloatArray(0),
    val controllerTempSeries: FloatArray = FloatArray(0),

    // Export feedback
    val exportInProgress: Boolean = false,
    val exportedUri: String? = null,
    val exportedName: String? = null,
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
            ratePerKwh == other.ratePerKwh &&
            startMs == other.startMs &&
            durationSec == other.durationSec &&
            distanceMeters == other.distanceMeters &&
            avgSpeedKmh10 == other.avgSpeedKmh10 &&
            maxSpeedKmh10 == other.maxSpeedKmh10 &&
            startBattery == other.startBattery &&
            endBattery == other.endBattery &&
            energyUsedWh == other.energyUsedWh &&
            energyRegenWh == other.energyRegenWh &&
            avgPowerW == other.avgPowerW &&
            maxPowerW == other.maxPowerW &&
            peakMotorTempC == other.peakMotorTempC &&
            peakBatteryTempC == other.peakBatteryTempC &&
            peakControllerTempC == other.peakControllerTempC &&
            dominantMode == other.dominantMode &&
            sampleCount == other.sampleCount &&
            isActive == other.isActive &&
            faults == other.faults &&
            speedSeries.size == other.speedSeries.size &&
            rpmSeries.size == other.rpmSeries.size &&
            voltageSeries.size == other.voltageSeries.size &&
            currentSeries.size == other.currentSeries.size &&
            powerSeries.size == other.powerSeries.size &&
            batterySeries.size == other.batterySeries.size &&
            temperatureSeries.size == other.temperatureSeries.size &&
            batteryTempSeries.size == other.batteryTempSeries.size &&
            controllerTempSeries.size == other.controllerTempSeries.size &&
            exportInProgress == other.exportInProgress &&
            exportedUri == other.exportedUri &&
            exportedName == other.exportedName &&
            exportError == other.exportError
    }

    override fun hashCode(): Int {
        var h = loading.hashCode()
        h = 31 * h + notFound.hashCode()
        h = 31 * h + tripId.hashCode()
        h = 31 * h + (ratePerKwh?.hashCode() ?: 0)
        h = 31 * h + startMs.hashCode()
        h = 31 * h + durationSec.hashCode()
        h = 31 * h + distanceMeters.hashCode()
        h = 31 * h + avgSpeedKmh10
        h = 31 * h + maxSpeedKmh10
        h = 31 * h + startBattery
        h = 31 * h + (endBattery ?: 0)
        h = 31 * h + energyUsedWh.hashCode()
        h = 31 * h + energyRegenWh.hashCode()
        h = 31 * h + avgPowerW.hashCode()
        h = 31 * h + maxPowerW.hashCode()
        h = 31 * h + peakMotorTempC
        h = 31 * h + peakBatteryTempC
        h = 31 * h + peakControllerTempC
        h = 31 * h + dominantMode.hashCode()
        h = 31 * h + sampleCount.hashCode()
        h = 31 * h + isActive.hashCode()
        h = 31 * h + faults.hashCode()
        h = 31 * h + speedSeries.size
        h = 31 * h + rpmSeries.size
        h = 31 * h + voltageSeries.size
        h = 31 * h + currentSeries.size
        h = 31 * h + powerSeries.size
        h = 31 * h + batterySeries.size
        h = 31 * h + temperatureSeries.size
        h = 31 * h + batteryTempSeries.size
        h = 31 * h + controllerTempSeries.size
        h = 31 * h + exportInProgress.hashCode()
        h = 31 * h + (exportedUri?.hashCode() ?: 0)
        h = 31 * h + (exportedName?.hashCode() ?: 0)
        h = 31 * h + (exportError?.hashCode() ?: 0)
        return h
    }
}

/**
 * A single fault/warning row for the Trip Detail "Faults" section. A flat,
 * pre-shaped presentation model so the composable never touches the persistence
 * entity. `severity` matches FaultEventEntity: 0 = info, 1 = warning, 2 = critical.
 */
@Immutable
data class TripFaultRow(
    val timestampMs: Long,
    val severity: Int,
    val type: String,
    val message: String
)
