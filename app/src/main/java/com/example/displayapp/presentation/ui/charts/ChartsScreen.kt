package com.example.displayapp.presentation.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.presentation.state.TimeRange
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.StatusChip
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.ChartsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvLime

@Composable
fun ChartsScreen(viewModel: ChartsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    ChartsContent(
        state = state,
        onRangeSelected = viewModel::setRange,
        onToggleMetric  = viewModel::toggleMetric,
        onFocusMetric   = viewModel::focusMetric,
        onExpand        = { isFullscreen = true }
    )
    if (isFullscreen) {
        ChartsFullscreen(
            state = state,
            onClose = { isFullscreen = false },
            onRangeSelected = viewModel::setRange,
            onToggleMetric  = viewModel::toggleMetric,
            onFocusMetric   = viewModel::focusMetric
        )
    }
}

@Composable
fun ChartsContent(
    state: ChartsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onToggleMetric:  (TelemetryMetric) -> Unit,
    onFocusMetric:   (TelemetryMetric) -> Unit,
    onExpand:        () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dim.screenGutter)
                .padding(top = Dim.screenTop, bottom = Dim.lg)
        ) {
            Header(state)
            Spacer(Modifier.height(Dim.lg))
            TelemetryCard(
                state = state,
                onRangeSelected = onRangeSelected,
                onToggleMetric  = onToggleMetric,
                onFocusMetric   = onFocusMetric,
                onExpand        = onExpand
            )
            Spacer(Modifier.height(Dim.xl))
        }
    }
}

@Composable
private fun Header(state: ChartsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Telemetry",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Realtime analytics · ${state.timestamps.size} samples",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusChip(
            text  = if (state.timestamps.isEmpty()) "Waiting" else "Live",
            color = if (state.timestamps.isEmpty()) EvAmber else EvLime
        )
    }
}

@Composable
private fun TelemetryCard(
    state: ChartsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onToggleMetric:  (TelemetryMetric) -> Unit,
    onFocusMetric:   (TelemetryMetric) -> Unit,
    onExpand:        () -> Unit
) {
    val currentRange = remember(state.rangeSec) {
        TimeRange.entries.firstOrNull { it.seconds == state.rangeSec } ?: TimeRange.SEC_60
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.md)) {
            FocusedMetricRow(state = state, onExpand = onExpand)
            TimeRangeSelector(
                selected = currentRange,
                onSelected = onRangeSelected,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            RealtimeLineChart(
                state = state,
                modifier = Modifier.fillMaxWidth()
            )
            MetricToggleChips(
                state    = state,
                onToggle = onToggleMetric,
                onFocus  = onFocusMetric
            )
        }
    }
}

@Composable
private fun FocusedMetricRow(state: ChartsUiState, onExpand: () -> Unit) {
    val metric  = state.focusedMetric
    val latest  = state.latestFor(metric)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(metric.color)
            )
            Column {
                Text(
                    text = "FOCUS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = metric.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (latest != null) metric.format.format(latest) else "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = metric.color
                )
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ExpandButton(onClick = onExpand)
        }
    }
}

@Composable
private fun ExpandButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = EvIcons.Fullscreen,
            contentDescription = "Open fullscreen chart",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp)
        )
    }
}
