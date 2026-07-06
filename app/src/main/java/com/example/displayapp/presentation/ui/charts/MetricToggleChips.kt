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
import com.example.displayapp.ui.theme.TelemetrySeriesStyle
import com.example.displayapp.ui.theme.seriesColor

@Composable
fun MetricToggleChips(
    state: ChartsUiState,
    onToggle: (TelemetryMetric) -> Unit,
    onFocus: (TelemetryMetric) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (compact) Dim.xs else Dim.sm)
    ) {
        for (metric in TelemetryMetric.displayable) {
            val selected = metric in state.selectedMetrics
            val focused  = selected && metric == state.focusedMetric
            MetricChip(
                metric = metric,
                color = metric.seriesColor(),
                latest = state.latestFor(metric),
                selected = selected,
                focused  = focused,
                compact  = compact,
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
    color: Color,
    latest: Float?,
    selected: Boolean,
    focused: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val border by animateColorAsState(
        targetValue = when {
            focused  -> color
            selected -> color.copy(alpha = 0.45f)
            else     -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        },
        label = "chipBorder"
    )
    val container by animateColorAsState(
        targetValue = when {
            focused  -> color.copy(alpha = 0.18f)
            selected -> color.copy(alpha = 0.08f)
            else     -> Color.Transparent
        },
        label = "chipContainer"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipLabel"
    )
    val dotSize by animateDpAsState(
        targetValue = if (focused) 11.dp else if (selected) 10.dp else 8.dp,
        label = "chipDot"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        label = "chipBorderW"
    )

    val dotColor = when {
        selected -> color
        else     -> color.copy(alpha = TelemetrySeriesStyle.DISABLED_DOT_ALPHA)
    }

    val hPad = if (compact) Dim.sm else Dim.md
    val vPad = if (compact) Dim.xs else Dim.sm

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(borderWidth, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) Dim.xs else Dim.sm)
    ) {
        Box(
            Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = metric.displayName,
            style = if (compact) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelMedium,
            color = labelColor
        )
        if (!compact && selected && latest != null) {
            Text(
                text = "${metric.format.format(latest)} ${metric.unit}",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}
