package com.example.displayapp.presentation.ui.charts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.ui.theme.Dim

@Composable
fun MetricToggleChips(
    state: ChartsUiState,
    onToggle: (TelemetryMetric) -> Unit,
    onFocus: (TelemetryMetric) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        for (metric in TelemetryMetric.entries) {
            val selected = metric in state.selectedMetrics
            val focused  = selected && metric == state.focusedMetric
            MetricChip(
                metric = metric,
                latest = state.latestFor(metric),
                selected = selected,
                focused  = focused,
                onClick = {
                    when {
                        !selected -> onFocus(metric)
                        !focused  -> onFocus(metric)
                        else      -> onToggle(metric)
                    }
                }
            )
        }
    }
}

@Composable
private fun MetricChip(
    metric: TelemetryMetric,
    latest: Float?,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    val border by animateColorAsState(
        targetValue = when {
            focused  -> metric.color
            selected -> metric.color.copy(alpha = 0.45f)
            else     -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        },
        label = "chipBorder"
    )
    val container by animateColorAsState(
        targetValue = when {
            focused  -> metric.color.copy(alpha = 0.18f)
            selected -> metric.color.copy(alpha = 0.08f)
            else     -> Color.Transparent
        },
        label = "chipContainer"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipLabel"
    )
    val dotAlpha by animateDpAsState(
        targetValue = if (selected) 10.dp else 8.dp,
        label = "chipDot"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        label = "chipBorderW"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(borderWidth, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Box(
            Modifier
                .size(dotAlpha)
                .clip(CircleShape)
                .background(if (selected) metric.color else metric.color.copy(alpha = 0.45f))
        )
        Text(
            text = metric.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor
        )
        if (selected && latest != null) {
            Text(
                text = "${metric.format.format(latest)} ${metric.unit}",
                style = MaterialTheme.typography.labelMedium,
                color = metric.color
            )
        }
    }
}
