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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val distKm = trip.distanceMeters / 1000f
        val avgSpd = trip.avgSpeedKmh10 / 10f
        val maxSpd = trip.maxSpeedKmh10 / 10f
        val battStart = trip.startBattery
        val battEnd   = trip.endBattery ?: battStart
        val deltaBatt = (battStart - battEnd).coerceAtLeast(0)
        val energyKwh = deltaBatt / 10f * 0.6f  // same heuristic as Logs row

        val speedSeries = FloatArray(samples.size) { samples[it].speed / 10f }
        val voltageSeries = FloatArray(samples.size) { samples[it].voltage / 100f }
        val currentSeries = FloatArray(samples.size) { samples[it].current / 100f }
        val temperatureSeries = FloatArray(samples.size) { samples[it].temperature.toFloat() }

        return TripDetailUiState(
            loading = false,
            notFound = false,
            tripId = trip.id,
            dateLabel = DATE_FMT.format(Date(trip.startTime)),
            durationLabel = formatDuration(durationSec),
            distanceLabel = "%.1f km".format(distKm),
            avgSpeedLabel = "%.0f km/h".format(avgSpd),
            maxSpeedLabel = "%.0f km/h".format(maxSpd),
            batteryDeltaLabel = "$battStart% → ${trip.endBattery ?: '—'}%",
            energyLabel = if (energyKwh > 0f) "≈ %.1f kWh".format(energyKwh) else "—",
            sampleCount = trip.sampleCount,
            isActive = trip.endTime == null,
            speedSeries = speedSeries,
            voltageSeries = voltageSeries,
            currentSeries = currentSeries,
            temperatureSeries = temperatureSeries
        )
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    private companion object {
        val DATE_FMT: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
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
