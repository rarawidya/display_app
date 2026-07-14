package com.innodrive.evdash.presentation.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import com.innodrive.evdash.data.sharing.ShareHelper
import com.innodrive.evdash.domain.repository.TripRepository
import com.innodrive.evdash.data.energy.EnergyFormatter
import com.innodrive.evdash.presentation.state.DateRange
import com.innodrive.evdash.presentation.state.LogsSummary
import com.innodrive.evdash.presentation.state.TelemetryMetric
import com.innodrive.evdash.ui.theme.seriesColor
import com.innodrive.evdash.presentation.state.LogsUiState
import com.innodrive.evdash.presentation.state.SortBy
import com.innodrive.evdash.presentation.state.TripFilter
import com.innodrive.evdash.presentation.ui.common.EmptyState
import com.innodrive.evdash.presentation.ui.common.GlassCard
import com.innodrive.evdash.presentation.ui.common.LocalAppSettings
import com.innodrive.evdash.presentation.ui.icons.EvIcons
import com.innodrive.evdash.presentation.viewmodel.LogsViewModel
import com.innodrive.evdash.ui.theme.Dim
import com.innodrive.evdash.ui.theme.EvBlue
import com.innodrive.evdash.ui.theme.EvRed

@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    tripRepository: TripRepository,
    onTripClick: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogsContent(
        state = state,
        tripRepository = tripRepository,
        onQuery = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onDateRange = viewModel::setDateRange,
        onSort = viewModel::setSortBy,
        onTripClick = onTripClick,
        onExport = viewModel::exportTrip,
        onDelete = viewModel::requestDelete,
        onUndo = viewModel::undoDelete,
        onUndoDismissed = viewModel::dismissUndo,
        onExportConsumed = viewModel::consumeExportResult
    )
}

@Composable
fun LogsContent(
    state: LogsUiState,
    tripRepository: TripRepository,
    onQuery: (String) -> Unit,
    onFilter: (TripFilter) -> Unit,
    onDateRange: (DateRange) -> Unit,
    onSort: (SortBy) -> Unit,
    onTripClick: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onUndo: () -> Unit,
    onUndoDismissed: () -> Unit,
    onExportConsumed: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Trip the user has swiped on, gated by an AlertDialog before the actual
    // delete fires. Cleared on confirm or cancel.
    var confirmingDeleteId by remember { mutableStateOf<Long?>(null) }
    if (confirmingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteId = null },
            title = { Text("Delete this trip?") },
            text = {
                Text("The trip and all of its telemetry samples will be removed permanently.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = confirmingDeleteId
                        confirmingDeleteId = null
                        if (id != null) onDelete(id)
                    }
                ) { Text("Delete", color = EvRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    // Export feedback — the CSV is saved to the phone's Downloads folder; the
    // "Open" action views it in Files / a spreadsheet app.
    LaunchedEffect(state.lastExportUri, state.lastExportError) {
        when {
            state.lastExportError != null -> {
                snackbar.showSnackbar("Export failed: ${state.lastExportError}")
                onExportConsumed()
            }
            state.lastExportUri != null -> {
                val label = state.lastExportName?.let { "Saved to Downloads · $it" } ?: "Saved to Downloads"
                val result = snackbar.showSnackbar(message = label, actionLabel = "Open")
                if (result == SnackbarResult.ActionPerformed) {
                    ShareHelper.openFile(context, Uri.parse(state.lastExportUri))
                }
                onExportConsumed()
            }
        }
    }

    // Undo Snackbar — shown when a swipe pends a delete. Auto-dismisses when
    // the VM's commit window expires (matched here so the Snackbar disappears).
    LaunchedEffect(state.pendingUndoTripId) {
        val id = state.pendingUndoTripId ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Trip deleted",
            actionLabel = "Undo",
            withDismissAction = true
        )
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed       -> onUndoDismissed()
        }
        // Note: if pendingUndoTripId is replaced (another swipe), Compose cancels
        // and restarts this LaunchedEffect with the new id automatically.
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dim.screenGutter)
                    .padding(top = Dim.screenTop)
            ) {
                Header(visibleCount = state.visibleTrips.size, totalCount = state.trips.size)
                Spacer(Modifier.height(Dim.md))

                SummaryBand(summary = state.summary)
                Spacer(Modifier.height(Dim.md))

                SearchBar(query = state.query, onQuery = onQuery)
                Spacer(Modifier.height(Dim.sm))

                DateRangeChips(current = state.dateRange, onSelect = onDateRange)
                Spacer(Modifier.height(Dim.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusFilterChips(current = state.filter, onSelect = onFilter)
                    SortMenu(current = state.sortBy, onSelect = onSort)
                }
                Spacer(Modifier.height(Dim.md))

                when {
                    state.trips.isEmpty() -> EmptyState(
                        icon = EvIcons.Logs,
                        title = "No trips yet",
                        subtitle = "Connect to a vehicle and start driving — recorded sessions will appear here."
                    )
                    state.visibleTrips.isEmpty() -> EmptyState(
                        icon = EvIcons.Search,
                        title = "Nothing matches",
                        subtitle = "Try clearing the search, switching the date range, or changing the filter."
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Dim.md),
                        contentPadding = PaddingValues(bottom = Dim.xxl)
                    ) {
                        items(state.visibleTrips, key = { it.id }) { row ->
                            SwipeableTripRow(
                                row = row,
                                tripRepository = tripRepository,
                                onClick = { onTripClick(row.id) },
                                onExport = { onExport(row.id) },
                                onRequestDelete = { confirmingDeleteId = row.id }
                            )
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Dim.lg)
            ) { data -> Snackbar(snackbarData = data) }

            AnimatedVisibility(
                visible = state.isExporting,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Exporting CSV…",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Header                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun Header(visibleCount: Int, totalCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Trips",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (totalCount == visibleCount) "$totalCount sessions"
                else "$visibleCount of $totalCount sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Summary band                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun SummaryBand(summary: LogsSummary) {
    val app = LocalAppSettings.current
    val distanceLabel = app.speedUnit.formatDistance(summary.totalDistanceMeters)
    val avgSpeedLabel = app.speedUnit.formatSpeed(summary.avgSpeedKmh10 / 10f)
    // Net energy consumed across the visible trips (SI Wh); "—" when no trip carries
    // energy data (all pre-energy rows). Auto-switches Wh ↔ kWh.
    val energyLabel = if (summary.totalEnergyKwh != 0f) {
        EnergyFormatter.formatEnergyWh(summary.totalEnergyKwh.toDouble() * 1000.0)
    } else "—"

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            SummaryStat("Trips", summary.tripCount.toString(), EvBlue, Modifier.weight(0.7f))
            // Theme-adaptive accent (bright EV-blue in dark, standard in light) so the
            // distance reads cleanly in both themes — the old fixed neon lime did not.
            SummaryStat("Distance", distanceLabel, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            SummaryStat("Avg speed", avgSpeedLabel, EvBlue, Modifier.weight(1f))
            SummaryStat("Energy", energyLabel, TelemetryMetric.Power.seriesColor(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.Start, modifier = modifier) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Search + chips + sort                                                     */
/* -------------------------------------------------------------------------- */

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        leadingIcon = {
            Icon(
                imageVector = EvIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        placeholder = { Text("Search trips") },
        singleLine = true,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun DateRangeChips(current: DateRange, onSelect: (DateRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        DateRange.entries.forEach { r ->
            FilterChip(
                selected = current == r,
                onClick = { onSelect(r) },
                label = { Text(r.label) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun StatusFilterChips(current: TripFilter, onSelect: (TripFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        TripFilter.entries.forEach { f ->
            FilterChip(
                selected = current == f,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun SortMenu(current: SortBy, onSelect: (SortBy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = EvIcons.MoreVert,
                contentDescription = "Sort by ${current.label}",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortBy.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = {
                        onSelect(s)
                        expanded = false
                    }
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Swipe-to-delete                                                           */
/* -------------------------------------------------------------------------- */

@Composable
private fun SwipeableTripRow(
    row: com.innodrive.evdash.presentation.state.TripRow,
    tripRepository: TripRepository,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onRequestDelete: () -> Unit
) {
    // Active trips can't be swiped-to-delete — they're still recording.
    if (row.isActive) {
        TripCard(
            row = row,
            tripRepository = tripRepository,
            onClick = onClick,
            onExport = onExport
        )
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        // Reject the dismiss (so the row snaps back) and surface a confirmation
        // dialog instead. The actual delete only happens after the user
        // confirms via AlertDialog in LogsContent.
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRequestDelete()
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Dim.cardCorner))
                    .background(EvRed.copy(alpha = 0.22f))
                    .padding(horizontal = Dim.lg),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = EvIcons.Trash,
                    contentDescription = "Delete",
                    tint = EvRed,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) {
        TripCard(
            row = row,
            tripRepository = tripRepository,
            onClick = onClick,
            onExport = onExport
        )
    }
}
