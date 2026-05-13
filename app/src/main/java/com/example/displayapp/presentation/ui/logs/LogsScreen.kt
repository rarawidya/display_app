package com.example.displayapp.presentation.ui.logs

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
import com.example.displayapp.data.sharing.ShareHelper
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.presentation.state.DateRange
import com.example.displayapp.presentation.state.LogsSummary
import com.example.displayapp.presentation.state.LogsUiState
import com.example.displayapp.presentation.state.SortBy
import com.example.displayapp.presentation.state.TripFilter
import com.example.displayapp.presentation.ui.common.EmptyState
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.LogsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvRed
import java.io.File

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

    // Export feedback — "Share" action opens the system share sheet.
    LaunchedEffect(state.lastExportPath, state.lastExportError) {
        when {
            state.lastExportError != null -> {
                snackbar.showSnackbar("Export failed: ${state.lastExportError}")
                onExportConsumed()
            }
            state.lastExportPath != null -> {
                val result = snackbar.showSnackbar(
                    message = "CSV saved",
                    actionLabel = "Share"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    ShareHelper.shareFile(context, File(state.lastExportPath))
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
    val energyLabel = if (summary.totalEnergyKwh > 0f) "%.1f kWh".format(summary.totalEnergyKwh) else "—"

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SummaryStat("Trips", summary.tripCount.toString(), EvBlue)
            SummaryStat("Distance", distanceLabel, EvLime)
            SummaryStat("Avg speed", avgSpeedLabel, EvBlue)
            SummaryStat("Energy", energyLabel, EvGreen)
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
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
    row: com.example.displayapp.presentation.state.TripRow,
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
