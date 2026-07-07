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
import androidx.compose.ui.graphics.PathEffect
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
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.ui.theme.LocalTelemetryPalette
import com.example.displayapp.ui.theme.TelemetrySeriesStyle

private const val Y_TICKS = 4
private const val X_TICKS = 4

@Composable
fun RealtimeLineChart(
    state: ChartsUiState,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp
) {
    val palette = LocalTelemetryPalette.current
    // Resolve all series colors up-front — DrawScope isn't @Composable.
    val seriesColors = remember(palette) {
        TelemetryMetric.entries.associateWith { palette.colorOf(it) }
    }
    // Captured into the DrawScope so Y-axis tick labels honor the mph/°F
    // preference (datasets stay SI; only the label converts).
    val appSettings = LocalAppSettings.current

    val axisColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val baselineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val gridMajor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val gridMinor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val labelColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle  = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val focusLabelStyle = MaterialTheme.typography.labelSmall.copy(
        color = seriesColors.getValue(state.focusedMetric)
    )
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
            val leftPad   = 56.dp.toPx()
            val rightPad  = 12.dp.toPx()
            val topPad    = 14.dp.toPx()
            val bottomPad = 30.dp.toPx()

            val plotLeft   = leftPad
            val plotTop    = topPad
            val plotRight  = size.width - rightPad
            val plotBottom = size.height - bottomPad
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            // Horizontal grid — major lines at each Y tick, dashed for minor read.
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            for (i in 0..Y_TICKS) {
                val y = plotTop + plotH * i / Y_TICKS
                val isEdge = i == 0 || i == Y_TICKS
                drawLine(
                    color = if (isEdge) gridMajor else gridMinor,
                    start = Offset(plotLeft, y),
                    end   = Offset(plotRight, y),
                    strokeWidth = 1f,
                    pathEffect = if (isEdge) null else dashEffect
                )
            }
            for (i in 1..X_TICKS) {
                val x = plotLeft + plotW * i / X_TICKS
                drawLine(
                    color = gridMinor,
                    start = Offset(x, plotTop),
                    end   = Offset(x, plotBottom),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
            }

            // Axes — left = focused metric (its color tints the Y baseline),
            // bottom = time. Slightly thicker than grid so they read as the
            // chart frame, not noise.
            drawLine(axisColor, Offset(plotLeft, plotTop),    Offset(plotLeft, plotBottom),  strokeWidth = 1.5f)
            drawLine(baselineColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1.5f)

            // Draw selected lines. Non-focused selected lines get a lower
            // alpha + thinner stroke so the focused metric reads first
            // during glance analysis.
            val focused = state.focusedMetric
            val focusedStroke = TelemetrySeriesStyle.FOCUSED_STROKE_DP
            val unfocusedStroke = TelemetrySeriesStyle.UNFOCUSED_STROKE_DP
            val unfocusedAlpha = TelemetrySeriesStyle.UNFOCUSED_ALPHA

            for (metric in TelemetryMetric.entries) {
                val alpha = alphas[metric] ?: 0f
                if (alpha <= 0.01f) continue
                val values = state.valuesFor(metric)
                if (values.size < 2) continue

                val (lo, hi) = niceRange(values, metric)
                val span = (hi - lo).coerceAtLeast(0.0001f)
                val isFocused = metric == focused
                val effectiveAlpha = if (isFocused) alpha else alpha * unfocusedAlpha
                val color = seriesColors.getValue(metric)
                val n = values.size
                val stepX = plotW / (n - 1)

                val path = paths.getValue(metric).also { it.reset() }
                // Rolling channels (Wh/km, range) emit NaN before they're
                // ready. Break the path on NaN so the line doesn't smear
                // across the gap.
                var penDown = false
                for (i in 0 until n) {
                    val v = values[i]
                    if (v.isNaN()) { penDown = false; continue }
                    val x = plotLeft + i * stepX
                    val y = plotBottom - ((v - lo) / span) * plotH
                    if (!penDown) { path.moveTo(x, y); penDown = true } else path.lineTo(x, y)
                }

                if (isFocused) {
                    fillPath.reset()
                    var fillPenDown = false
                    for (i in 0 until n) {
                        val v = values[i]
                        if (v.isNaN()) { fillPenDown = false; continue }
                        val x = plotLeft + i * stepX
                        val y = plotBottom - ((v - lo) / span) * plotH
                        if (!fillPenDown) { fillPath.moveTo(x, plotBottom); fillPenDown = true }
                        fillPath.lineTo(x, y)
                    }
                    fillPath.lineTo(plotLeft + (n - 1) * stepX, plotBottom)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                color.copy(alpha = 0.28f * alpha),
                                Color.Transparent
                            ),
                            startY = plotTop,
                            endY   = plotBottom
                        )
                    )
                }

                drawPath(
                    path = path,
                    color = color.copy(alpha = effectiveAlpha),
                    style = Stroke(
                        width = if (isFocused) focusedStroke.dp.toPx() else unfocusedStroke.dp.toPx(),
                        cap   = StrokeCap.Round,
                        join  = StrokeJoin.Round
                    )
                )

                // Marker dot at the most recent non-NaN sample.
                var lastIdx = n - 1
                while (lastIdx >= 0 && values[lastIdx].isNaN()) lastIdx--
                if (lastIdx >= 0) {
                    val lastX = plotLeft + lastIdx * stepX
                    val lastY = plotBottom - ((values[lastIdx] - lo) / span) * plotH
                    val haloR = if (isFocused) 13f else 9f
                    val dotR  = if (isFocused) 4.5f else 3.5f
                    drawCircle(color.copy(alpha = 0.22f * effectiveAlpha), radius = haloR, center = Offset(lastX, lastY))
                    drawCircle(color.copy(alpha = effectiveAlpha),         radius = dotR,  center = Offset(lastX, lastY))
                }
            }

            // Y tick labels — colored to match the focused metric so the
            // viewer instantly knows which series the axis is reading.
            val focusValues = state.valuesFor(state.focusedMetric)
            if (focusValues.isNotEmpty()) {
                val (lo, hi) = niceRange(focusValues, state.focusedMetric)
                for (i in 0..Y_TICKS) {
                    val frac = i.toFloat() / Y_TICKS
                    val tickValue = hi - (hi - lo) * frac
                    val y = plotTop + plotH * frac
                    drawAxisLabel(
                        measurer = measurer,
                        text = displayLatest(state.focusedMetric, tickValue, appSettings).first,
                        style = focusLabelStyle,
                        anchorX = plotLeft - 8.dp.toPx(),
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
        if (v.isNaN()) continue   // rolling channels (Wh/km, range) emit NaN until ready
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (lo.isInfinite() || hi.isInfinite()) return 0f to 1f
    val span = hi - lo
    val pad = if (span < 1f) 1f else span * 0.12f
    return (lo - pad) to (hi + pad)
}
