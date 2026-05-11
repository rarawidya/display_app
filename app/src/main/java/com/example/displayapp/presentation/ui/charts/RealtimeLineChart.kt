package com.example.displayapp.presentation.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TelemetryMetric

private const val Y_TICKS = 4
private const val X_TICKS = 4

@Composable
fun RealtimeLineChart(
    state: ChartsUiState,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp
) {
    val axisColor   = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val gridColor   = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val labelColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle  = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val measurer    = rememberTextMeasurer()

    val alphas = TelemetryMetric.entries.associateWith { metric ->
        animateFloatAsState(
            targetValue = if (metric in state.selectedMetrics) 1f else 0f,
            animationSpec = tween(durationMillis = 280),
            label = "alpha-${metric.name}"
        ).value
    }

    val paths = remember { TelemetryMetric.entries.associateWith { Path() } }
    val fillPath = remember { Path() }

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        if (state.timestamps.size < 2 && state.selectedMetrics.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Waiting for telemetry…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = emptyColor
                )
            }
            return@Box
        }
        if (state.selectedMetrics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a metric below to plot",
                    style = MaterialTheme.typography.bodyMedium,
                    color = emptyColor
                )
            }
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPad   = 52.dp.toPx()
            val rightPad  = 12.dp.toPx()
            val topPad    = 14.dp.toPx()
            val bottomPad = 28.dp.toPx()

            val plotLeft   = leftPad
            val plotTop    = topPad
            val plotRight  = size.width - rightPad
            val plotBottom = size.height - bottomPad
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            for (i in 0..Y_TICKS) {
                val y = plotTop + plotH * i / Y_TICKS
                drawLine(
                    color = gridColor,
                    start = Offset(plotLeft, y),
                    end   = Offset(plotRight, y),
                    strokeWidth = 1f
                )
            }
            for (i in 1..X_TICKS) {
                val x = plotLeft + plotW * i / X_TICKS
                drawLine(
                    color = gridColor,
                    start = Offset(x, plotTop),
                    end   = Offset(x, plotBottom),
                    strokeWidth = 1f
                )
            }

            drawLine(axisColor, Offset(plotLeft, plotTop),    Offset(plotLeft, plotBottom),  strokeWidth = 1.5f)
            drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1.5f)

            for (metric in TelemetryMetric.entries) {
                val alpha = alphas[metric] ?: 0f
                if (alpha <= 0.01f) continue
                val values = state.valuesFor(metric)
                if (values.size < 2) continue

                val (lo, hi) = niceRange(values, metric)
                val span = (hi - lo).coerceAtLeast(0.0001f)
                val isFocused = metric == state.focusedMetric
                val n = values.size
                val stepX = plotW / (n - 1)

                val path = paths.getValue(metric).also { it.reset() }
                for (i in 0 until n) {
                    val x = plotLeft + i * stepX
                    val y = plotBottom - ((values[i] - lo) / span) * plotH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                if (isFocused) {
                    fillPath.reset()
                    fillPath.moveTo(plotLeft, plotBottom)
                    for (i in 0 until n) {
                        val x = plotLeft + i * stepX
                        val y = plotBottom - ((values[i] - lo) / span) * plotH
                        fillPath.lineTo(x, y)
                    }
                    fillPath.lineTo(plotLeft + (n - 1) * stepX, plotBottom)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                metric.color.copy(alpha = 0.22f * alpha),
                                Color.Transparent
                            ),
                            startY = plotTop,
                            endY   = plotBottom
                        )
                    )
                }

                drawPath(
                    path = path,
                    color = metric.color.copy(alpha = alpha),
                    style = Stroke(
                        width = if (isFocused) 2.8f else 2f,
                        cap   = StrokeCap.Round,
                        join  = StrokeJoin.Round
                    )
                )

                val lastX = plotLeft + (n - 1) * stepX
                val lastY = plotBottom - ((values.last() - lo) / span) * plotH
                drawCircle(metric.color.copy(alpha = 0.22f * alpha), radius = 11f, center = Offset(lastX, lastY))
                drawCircle(metric.color.copy(alpha = alpha),         radius = 4f,  center = Offset(lastX, lastY))
            }

            val focusValues = state.valuesFor(state.focusedMetric)
            if (focusValues.isNotEmpty()) {
                val (lo, hi) = niceRange(focusValues, state.focusedMetric)
                for (i in 0..Y_TICKS) {
                    val frac = i.toFloat() / Y_TICKS
                    val tickValue = hi - (hi - lo) * frac
                    val y = plotTop + plotH * frac
                    drawAxisLabel(
                        measurer = measurer,
                        text = state.focusedMetric.format.format(tickValue),
                        style = labelStyle,
                        anchorX = plotLeft - 6.dp.toPx(),
                        centerY = y,
                        alignEnd = true
                    )
                }
            }

            for (i in 0..X_TICKS) {
                val frac = i.toFloat() / X_TICKS
                val secondsAgo = (state.rangeSec * (1f - frac)).toInt()
                val text = if (secondsAgo == 0) "now" else "-${secondsAgo}s"
                val x = plotLeft + plotW * frac
                drawAxisLabel(
                    measurer = measurer,
                    text = text,
                    style = labelStyle,
                    anchorX = x,
                    centerY = plotBottom + 14.dp.toPx(),
                    alignEnd = false
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    anchorX: Float,
    centerY: Float,
    alignEnd: Boolean
) {
    val layout = measurer.measure(text, style)
    val x = if (alignEnd) anchorX - layout.size.width else anchorX - layout.size.width / 2f
    val y = centerY - layout.size.height / 2f
    drawText(textLayoutResult = layout, topLeft = Offset(x, y))
}

private fun niceRange(values: List<Float>, metric: TelemetryMetric): Pair<Float, Float> {
    metric.fixedRange?.let { return it.start to it.endInclusive }
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (v in values) {
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    val span = hi - lo
    val pad = if (span < 1f) 1f else span * 0.12f
    return (lo - pad) to (hi + pad)
}
