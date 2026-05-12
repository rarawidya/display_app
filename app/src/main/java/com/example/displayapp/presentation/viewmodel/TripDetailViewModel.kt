package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.presentation.state.TripDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

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
            _state.value = buildState(trip, samples)
        }
    }

    fun export() {
        val tripId = _state.value.tripId
        if (tripId < 0) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exportInProgress = true, exportError = null)
            val file: File? = try {
                withContext(Dispatchers.IO) { exporter.exportTrip(tripId) }
            } catch (t: Throwable) {
                Timber.e(t, "exportTrip failed")
                null
            }
            _state.value = _state.value.copy(
                exportInProgress = false,
                exportedFilePath = file?.absolutePath,
                exportError = if (file == null) "Export failed" else null
            )
        }
    }

    /** Clears one-shot export-result fields after the UI has consumed them. */
    fun consumeExportResult() {
        _state.value = _state.value.copy(exportedFilePath = null, exportError = null)
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

    private fun buildState(trip: TripEntity, samples: List<TelemetryEntity>): TripDetailUiState {
        val durationSec = ((trip.endTime ?: System.currentTimeMillis()) - trip.startTime) / 1000L

        // Chart series stay in SI units; the UI converts at the label site.
        val speedSeries = FloatArray(samples.size) { samples[it].speed / 10f }
        val voltageSeries = FloatArray(samples.size) { samples[it].voltage / 100f }
        val currentSeries = FloatArray(samples.size) { samples[it].current / 100f }
        val temperatureSeries = FloatArray(samples.size) { samples[it].temperature.toFloat() }

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
            sampleCount = trip.sampleCount,
            isActive = trip.endTime == null,
            speedSeries = speedSeries,
            voltageSeries = voltageSeries,
            currentSeries = currentSeries,
            temperatureSeries = temperatureSeries
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
