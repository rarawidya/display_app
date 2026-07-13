package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.data.protocol.TelemetryDerivations
import com.example.displayapp.domain.model.VehicleMode
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.presentation.state.TripDetailUiState
import com.example.displayapp.presentation.state.TripFaultRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the Trip Detail screen state.
 *
 * - On `load(tripId)`, fetches the trip and its downsampled telemetry off the
 *   main thread, formats hero stats, and computes four chart series as
 *   FloatArrays.
 * - Exposes `export()` and `delete()` so the detail screen can act on the
 *   currently-loaded trip without re-passing the id.
 *
 * Why downsample on load?
 *   At 20 Hz, a 1-hour trip has ~72k samples — far more than the chart canvas
 *   has pixels. Downsampling to ~600 points (every 120th row) renders
 *   identically while keeping the draw loop fast and recompositions cheap.
 */
class TripDetailViewModel(
    private val repository: TripRepository,
    private val exporter: CsvExporter
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailUiState())
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    fun load(tripId: Long) {
        viewModelScope.launch {
            _state.value = TripDetailUiState(loading = true, tripId = tripId)
            val trip = withContext(Dispatchers.IO) { repository.getTrip(tripId) }
            if (trip == null) {
                _state.value = TripDetailUiState(loading = false, notFound = true, tripId = tripId)
                return@launch
            }
            // Downsample to ~600 chart points regardless of trip length.
            val target = 600
            val totalSamples = trip.sampleCount.coerceAtLeast(1).toInt()
            val sampleEvery = (totalSamples / target).coerceAtLeast(1)
            val samples = withContext(Dispatchers.IO) {
                repository.getDownsampledTelemetry(tripId, sampleEvery)
            }
            val faults = withContext(Dispatchers.IO) {
                repository.getTripFaults(tripId).map {
                    TripFaultRow(
                        timestampMs = it.timestamp,
                        severity = it.severity,
                        type = it.type,
                        message = it.message
                    )
                }
            }
            _state.value = buildState(trip, samples, faults)
        }
    }

    fun export() {
        val tripId = _state.value.tripId
        if (tripId < 0) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exportInProgress = true, exportError = null)
            val result = try {
                withContext(Dispatchers.IO) { exporter.exportTripToDownloads(tripId) }
            } catch (t: Throwable) {
                Timber.e(t, "exportTrip failed")
                null
            }
            _state.value = _state.value.copy(
                exportInProgress = false,
                exportedUri = result?.uri?.toString(),
                exportedName = result?.displayName,
                exportError = if (result == null) "Export failed" else null
            )
        }
    }

    /** Clears one-shot export-result fields after the UI has consumed them. */
    fun consumeExportResult() {
        _state.value = _state.value.copy(exportedUri = null, exportedName = null, exportError = null)
    }

    fun delete(onDone: () -> Unit) {
        val tripId = _state.value.tripId
        if (tripId < 0) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteTrip(tripId) }
            onDone()
        }
    }

    /* ---------------------------------------------------------------------- */
    /*  State assembly                                                        */
    /* ---------------------------------------------------------------------- */

    private fun buildState(
        trip: TripEntity,
        samples: List<TelemetryEntity>,
        faults: List<TripFaultRow>
    ): TripDetailUiState {
        val durationSec = ((trip.endTime ?: System.currentTimeMillis()) - trip.startTime) / 1000L

        // Chart series stay in SI; the UI converts at the label site only.
        // Decoding through TelemetryDerivations.decodeEntity is the single
        // canonical path — rpm and power come out matching live Drive /
        // Charts / replay for the same wire values. No Trip-Detail-local
        // formula.
        val decoded = Array(samples.size) { TelemetryDerivations.decodeEntity(samples[it]) }
        val speedSeries = FloatArray(samples.size) { samples[it].speed / 10f }
        val rpmSeries = FloatArray(samples.size) { decoded[it].rpm.toFloat() }
        val voltageSeries = FloatArray(samples.size) { decoded[it].voltage }
        val currentSeries = FloatArray(samples.size) { decoded[it].current }
        val powerSeries = FloatArray(samples.size) { decoded[it].power }
        val batterySeries = FloatArray(samples.size) { decoded[it].batteryPercent.toFloat() }
        val temperatureSeries = FloatArray(samples.size) { decoded[it].temperature.toFloat() }
        val batteryTempSeries = FloatArray(samples.size) { decoded[it].batteryTemperature.toFloat() }
        val controllerTempSeries = FloatArray(samples.size) { decoded[it].controllerTemperature.toFloat() }

        // Dominant mode across the trip. Computed off the downsampled samples
        // (~600 points), which is more than enough resolution to identify the
        // longest-running mode without re-querying every row.
        val modeCounts = IntArray(VehicleMode.entries.size)
        for (s in samples) {
            val idx = s.mode
            if (idx in modeCounts.indices) modeCounts[idx]++
        }
        val dominantIdx = modeCounts.withIndex().maxByOrNull { it.value }?.index ?: 0
        val dominantMode = VehicleMode.entries.getOrElse(dominantIdx) { VehicleMode.PARK }

        return TripDetailUiState(
            loading = false,
            notFound = false,
            tripId = trip.id,
            // ratePerKwh stays null until a Settings input is wired (Phase 3).
            startMs = trip.startTime,
            durationSec = durationSec,
            distanceMeters = trip.distanceMeters,
            avgSpeedKmh10 = trip.avgSpeedKmh10,
            maxSpeedKmh10 = trip.maxSpeedKmh10,
            startBattery = trip.startBattery,
            endBattery = trip.endBattery,
            energyUsedWh = trip.energyUsedWh,
            energyRegenWh = trip.energyRegenWh,
            avgPowerW = trip.avgPowerW100 / 100f,
            maxPowerW = trip.maxPowerW100 / 100f,
            peakMotorTempC = trip.peakMotorTempC,
            peakBatteryTempC = trip.peakBatteryTempC,
            peakControllerTempC = trip.peakControllerTempC,
            dominantMode = dominantMode,
            sampleCount = trip.sampleCount,
            isActive = trip.endTime == null,
            faults = faults,
            speedSeries = speedSeries,
            rpmSeries = rpmSeries,
            voltageSeries = voltageSeries,
            currentSeries = currentSeries,
            powerSeries = powerSeries,
            batterySeries = batterySeries,
            temperatureSeries = temperatureSeries,
            batteryTempSeries = batteryTempSeries,
            controllerTempSeries = controllerTempSeries
        )
    }
}

class TripDetailViewModelFactory(
    private val repository: TripRepository,
    private val exporter: CsvExporter
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TripDetailViewModel::class.java)) {
            return TripDetailViewModel(repository, exporter) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
