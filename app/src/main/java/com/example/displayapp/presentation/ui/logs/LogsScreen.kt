package com.example.displayapp.presentation.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.presentation.state.LogsUiState
import com.example.displayapp.presentation.state.TripFilter
import com.example.displayapp.presentation.ui.common.EmptyState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.LogsViewModel
import com.example.displayapp.ui.theme.Dim

@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onTripClick: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogsContent(
        state = state,
        onQuery = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onTripClick = onTripClick,
        onExport = viewModel::exportTrip
    )
}

@Composable
fun LogsContent(
    state: LogsUiState,
    onQuery: (String) -> Unit,
    onFilter: (TripFilter) -> Unit,
    onTripClick: (Long) -> Unit,
    onExport: (Long) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.lastExportPath, state.lastExportError) {
        when {
            state.lastExportError != null -> snackbar.showSnackbar("Export failed: ${state.lastExportError}")
            state.lastExportPath  != null -> snackbar.showSnackbar("Saved: ${state.lastExportPath}")
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Dim.screenGutter)
        ) {
            Header(visibleCount = state.visibleTrips.size, totalCount = state.trips.size)

            Spacer(Modifier.height(Dim.md))

            SearchBar(query = state.query, onQuery = onQuery)

            Spacer(Modifier.height(Dim.md))

            FilterRow(filter = state.filter, onFilter = onFilter)

            Spacer(Modifier.height(Dim.md))

            if (state.trips.isEmpty()) {
                EmptyState(
                    icon = EvIcons.Logs,
                    title = "No trips yet",
                    subtitle = "Connect to a vehicle and start driving — recorded sessions will appear here."
                )
            } else if (state.visibleTrips.isEmpty()) {
                EmptyState(
                    icon = EvIcons.Search,
                    title = "Nothing matches",
                    subtitle = "Try clearing the search or switching the filter."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Dim.md),
                    contentPadding = PaddingValues(bottom = Dim.xxl)
                ) {
                    items(state.visibleTrips, key = { it.id }) { row ->
                        TripCard(
                            row = row,
                            onClick = { onTripClick(row.id) },
                            onExport = { onExport(row.id) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .fillMaxWidth()
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

@Composable
private fun Header(visibleCount: Int, totalCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Dim.sm),
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
private fun FilterRow(filter: TripFilter, onFilter: (TripFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        TripFilter.entries.forEach { f ->
            FilterChip(
                selected = filter == f,
                onClick = { onFilter(f) },
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
