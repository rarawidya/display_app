package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.persistence.export.CsvExporter
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.presentation.state.DateRange
import com.example.displayapp.presentation.state.LogsUiState
import com.example.displayapp.presentation.state.SortBy
import com.example.displayapp.presentation.state.TripFilter
import com.example.displayapp.presentation.state.toRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Logs/Trips ViewModel.
 *
 * Architecture:
 * - Reads trips from [TripRepository] (which wraps the Room DAO) so the data
 *   source can later be swapped for remote sync without touching this VM.
 * - Combines the trip stream with five UI controls (search, status filter,
 *   date range, sort, export state) plus a pending-delete set used for the
 *   swipe-to-delete + undo flow.
 * - Pending deletes are *deferred* — they live in memory while the undo
 *   Snackbar is visible, and only commit to the DB when the window expires
 *   or the user navigates away. Hitting Undo cancels the commit job.
 */
class LogsViewModel(
    private val tripRepository: TripRepository,
    private val exporter: CsvExporter
) : ViewModel() {

    private val _query     = MutableStateFlow("")
    private val _filter    = MutableStateFlow(TripFilter.ALL)
    private val _dateRange = MutableStateFlow(DateRange.ALL)
    private val _sortBy    = MutableStateFlow(SortBy.NEWEST)
    private val _pendingDelete = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingUndo   = MutableStateFlow<Long?>(null)
    private val _exportState   = MutableStateFlow(ExportState())

    private val tripsFlow = tripRepository.observeAllTrips()
        .map { list -> list.map { it.toRow() } }

    // Two layers of combine to stay under Compose-friendly limits (combine has
    // overloads up to 5 — we have 7 inputs).
    private val controls = combine(
        _query, _filter, _dateRange, _sortBy, _pendingDelete
    ) { q, f, range, sort, pending -> Controls(q, f, range, sort, pending) }

    val uiState: StateFlow<LogsUiState> = combine(
        tripsFlow, controls, _exportState, _pendingUndo
    ) { trips, c, export, pendingUndo ->
        LogsUiState(
            trips = trips,
            query = c.query,
            filter = c.filter,
            dateRange = c.dateRange,
            sortBy = c.sortBy,
            pendingDeleteIds = c.pendingDeleteIds,
            isExporting = export.inProgress,
            lastExportUri = export.lastUri,
            lastExportName = export.lastName,
            lastExportError = export.lastError,
            pendingUndoTripId = pendingUndo
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LogsUiState()
    )

    /* ---------------------------------------------------------------------- */
    /*  Filter / sort / search actions                                        */
    /* ---------------------------------------------------------------------- */

    fun setQuery(q: String)          { _query.value = q }
    fun setFilter(f: TripFilter)     { _filter.value = f }
    fun setDateRange(r: DateRange)   { _dateRange.value = r }
    fun setSortBy(s: SortBy)         { _sortBy.value = s }

    /* ---------------------------------------------------------------------- */
    /*  Swipe-to-delete + Undo                                                */
    /* ---------------------------------------------------------------------- */

    // One commit job PER pending trip. A single shared job was wrong: swiping a
    // second trip within the undo window cancelled the first trip's commit while
    // it was still hidden from the list — so it was neither deleted nor visible
    // (orphaned until the VM was recreated). Keyed by trip id so each commits
    // independently and Undo only cancels its own.
    private val commitDeleteJobs = mutableMapOf<Long, Job>()

    /**
     * Marks a trip as pending-delete (hides it from the list immediately) and
     * schedules the actual DB delete after [UNDO_WINDOW_MS]. The Undo button
     * on the Snackbar calls [undoDelete] to cancel the commit.
     */
    fun requestDelete(tripId: Long) {
        _pendingDelete.value = _pendingDelete.value + tripId
        _pendingUndo.value = tripId
        commitDeleteJobs.remove(tripId)?.cancel()
        commitDeleteJobs[tripId] = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitDelete(tripId)
            commitDeleteJobs.remove(tripId)
        }
    }

    fun undoDelete() {
        val id = _pendingUndo.value ?: return
        commitDeleteJobs.remove(id)?.cancel()
        _pendingDelete.value = _pendingDelete.value - id
        _pendingUndo.value = null
    }

    /** Clears the undo banner without undoing — used when navigating away. */
    fun dismissUndo() {
        _pendingUndo.value = null
    }

    private suspend fun commitDelete(tripId: Long) {
        withContext(Dispatchers.IO) { tripRepository.deleteTrip(tripId) }
        _pendingDelete.value = _pendingDelete.value - tripId
        if (_pendingUndo.value == tripId) _pendingUndo.value = null
    }

    /* ---------------------------------------------------------------------- */
    /*  Export                                                                */
    /* ---------------------------------------------------------------------- */

    fun exportTrip(id: Long) {
        viewModelScope.launch {
            _exportState.value = _exportState.value.copy(inProgress = true, lastError = null)
            try {
                val result = withContext(Dispatchers.IO) { exporter.exportTripToDownloads(id) }
                _exportState.value = ExportState(
                    inProgress = false,
                    lastUri = result?.uri?.toString(),
                    lastName = result?.displayName,
                    lastError = if (result == null) "Export returned no file" else null
                )
            } catch (t: Throwable) {
                Timber.e(t, "exportTrip failed")
                _exportState.value = ExportState(inProgress = false, lastError = t.message)
            }
        }
    }

    fun consumeExportResult() {
        _exportState.value = _exportState.value.copy(lastUri = null, lastName = null, lastError = null)
    }

    /* ---------------------------------------------------------------------- */
    /*  Internal types                                                        */
    /* ---------------------------------------------------------------------- */

    private data class Controls(
        val query: String,
        val filter: TripFilter,
        val dateRange: DateRange,
        val sortBy: SortBy,
        val pendingDeleteIds: Set<Long>
    )

    private data class ExportState(
        val inProgress: Boolean = false,
        val lastUri: String? = null,
        val lastName: String? = null,
        val lastError: String? = null
    )

    private companion object {
        const val UNDO_WINDOW_MS = 4_000L
    }
}
