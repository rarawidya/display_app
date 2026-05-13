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
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.presentation.state.TimeRange
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.common.StatusChip
import com.example.displayapp.presentation.ui.components.mode.ModeBadge
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.ChartsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.seriesColor

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

/**
 * Two-row Charts header.
 *
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │ Telemetry                                          ● Live      │  ← primary
 *   │ Realtime analytics · 80 samples                                │
 *   │                                                                │
 *   │ ● NORMAL                                                       │  ← secondary
 *   └───────────────────────────────────────────────────────────────┘
 *
 * The realtime status sits on its own line, uncluttered, with the Live /
 * Waiting chip aligned to its right — the primary "is data flowing" cue.
 * The mode badge is demoted to a separate row underneath so it stops
 * competing with the status text for visual weight.
 *
 * `Modifier.weight(1f)` on the title block lets long subtext wrap above
 * the chip instead of pushing it off-screen on narrow phones.
 */
@Composable
private fun Header(state: ChartsUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
        // Mirrors the Drive page's active mode via the same canonical
        // [com.example.displayapp.domain.model.VehicleData] stream. The
        // badge owns no state — it reads `state.vehicleMode` and renders.
        ModeBadge(mode = state.vehicleMode)
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

/**
 * Converts the raw latest value into the user's chosen unit + supplies the
 * matching suffix. Voltage / Current / Battery are unit-invariant.
 */
private fun displayLatest(
    metric: TelemetryMetric,
    latest: Float?,
    app: AppSettings
): Pair<String, String> {
    if (latest == null) return "—" to metric.unit
    return when (metric) {
        TelemetryMetric.Speed,
        TelemetryMetric.EstRange -> {
            val v = app.speedUnit.convertFromKmh(latest)
            val suffix = if (metric == TelemetryMetric.Speed) app.speedUnit.suffix else app.speedUnit.distanceSuffix
            metric.format.format(v) to suffix
        }
        TelemetryMetric.EngineTemp,
        TelemetryMetric.BatteryTemp,
        TelemetryMetric.ControllerTemp -> {
            val v = app.temperatureUnit.convertFromCelsius(latest)
            metric.format.format(v) to app.temperatureUnit.suffix
        }
        else -> metric.format.format(latest) to metric.unit
    }
}

@Composable
private fun FocusedMetricRow(state: ChartsUiState, onExpand: () -> Unit) {
    val metric  = state.focusedMetric
    val latest  = state.latestFor(metric)
    val app = LocalAppSettings.current
    val color = metric.seriesColor()
    val (latestText, unitText) = displayLatest(metric, latest, app)
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
                    .background(color)
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
                    text = latestText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = color
                )
                Text(
                    text = unitText,
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
