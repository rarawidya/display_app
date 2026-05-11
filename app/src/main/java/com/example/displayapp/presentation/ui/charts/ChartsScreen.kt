package com.example.displayapp.presentation.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TimeRange
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.SectionHeader
import com.example.displayapp.presentation.ui.common.StatusChip
import com.example.displayapp.presentation.viewmodel.ChartsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvCyan
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvRed
import com.example.displayapp.ui.theme.EvViolet

/**
 * Charts tab — five realtime line plots stacked vertically with a time-range
 * selector pinned at the top. Each plot is autonomous (own GlassCard + its
 * own series); they share the same window so the eye can compare events.
 */
@Composable
fun ChartsScreen(viewModel: ChartsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChartsContent(
        state = state,
        onRangeSelected = viewModel::setRange
    )
}

@Composable
fun ChartsContent(
    state: ChartsUiState,
    onRangeSelected: (TimeRange) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Dim.screenGutter, vertical = Dim.sm)
        ) {
            Header(state)

            Spacer(Modifier.height(Dim.md))

            val current = remember(state.rangeSec) {
                TimeRange.entries.firstOrNull { it.seconds == state.rangeSec } ?: TimeRange.SEC_60
            }
            TimeRangeSelector(
                selected = current,
                onSelected = onRangeSelected,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(Dim.lg))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                ChartCard("Speed", "km/h", state.speed, EvCyan, latestFormat = "%.0f")
                ChartCard("Voltage", "V", state.voltage, EvLime, latestFormat = "%.1f")
                ChartCard("Current", "A", state.current, EvAmber, latestFormat = "%.1f")
                ChartCard("Temperature", "°C", state.temperature, EvRed, latestFormat = "%.0f")
                ChartCard("Battery", "%", state.battery, EvViolet, latestFormat = "%.0f", minSpan = 5f)
                Spacer(Modifier.height(Dim.lg))
            }
        }
    }
}

@Composable
private fun Header(state: ChartsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Dim.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Charts",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Realtime telemetry · ${state.timestamps.size} samples",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusChip(
            text = if (state.timestamps.isEmpty()) "Waiting" else "Live",
            color = if (state.timestamps.isEmpty()) EvAmber else EvLime
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    data: List<Float>,
    color: Color,
    latestFormat: String = "%.1f",
    minSpan: Float = 1f
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
            SectionHeader(
                title = title,
                trailing = {
                    val latest = data.lastOrNull()
                    Text(
                        text = if (latest != null) "${latestFormat.format(latest)} $unit" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = color
                    )
                }
            )
            RealtimeLineChart(
                data = data,
                color = color,
                minSpan = minSpan
            )
        }
    }
}
