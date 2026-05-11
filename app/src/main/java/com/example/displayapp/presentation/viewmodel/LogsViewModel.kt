package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.presentation.state.LogsUiState
import com.example.displayapp.presentation.state.TripFilter
import com.example.displayapp.presentation.state.toRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ViewModel for the Logs tab.
 *
 * Reads trips from [TripDao.observeAll] (a Flow), maps each entity into a
 * pre-formatted [TripRow] off the main thread, and combines the result with
 * the local UI controls (search query, filter, export progress).
 *
 * Why pre-format on the data side?
 * - LazyColumn item composition cost stays microscopic — no String.format calls
 *   per visible item per recomposition.
 * - The LogsUiState is @Immutable, so identical references skip recomposition.
 */
class LogsViewModel(
    private val tripDao: TripDao,
    private val exporter: CsvExporter
) : ViewModel() {

    private val _query   = MutableStateFlow("")
    private val _filter  = MutableStateFlow(TripFilter.ALL)
    private val _exportState = MutableStateFlow(ExportState())

    private val tripsFlow = tripDao.observeAll().map { list -> list.map { it.toRow() } }

    val uiState: StateFlow<LogsUiState> = combine(
        tripsFlow, _query, _filter, _exportState
    ) { trips, query, filter, export ->
        LogsUiState(
            trips = trips,
            query = query,
            filter = filter,
            isExporting = export.inProgress,
            lastExportPath = export.lastPath,
            lastExportError = export.lastError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LogsUiState()
    )

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: TripFilter) { _filter.value = f }

    fun exportTrip(id: Long) {
        viewModelScope.launch {
            _exportState.value = _exportState.value.copy(inProgress = true, lastError = null)
            try {
                val file = withContext(Dispatchers.IO) { exporter.exportTrip(id) }
                _exportState.value = ExportState(
                    inProgress = false,
                    lastPath = file?.absolutePath,
                    lastError = if (file == null) "Export returned no file" else null
                )
            } catch (t: Throwable) {
                Timber.e(t, "exportTrip failed")
                _exportState.value = ExportState(inProgress = false, lastError = t.message)
            }
        }
    }

    fun deleteTrip(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            tripDao.deleteById(id)
        }
    }

    private data class ExportState(
        val inProgress: Boolean = false,
        val lastPath: String? = null,
        val lastError: String? = null
    )
}
