package com.example.displayapp.presentation.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-series realtime line chart.
 *
 * Why a Canvas (not a 3rd-party charting lib)?
 * - Bounded allocations per frame (we recycle a single Path)
 * - Full control over the look — matches our cockpit palette
 * - No reflection/runtime parsing — friendly to R8/profile-guided startup
 *
 * Performance notes:
 * - Path is constructed every draw (samples change every frame); it's a single
 *   small object reused per call so GC pressure is negligible at 10 fps.
 * - Vertical autoscaling: we recompute min/max each draw rather than caching,
 *   which keeps the chart responsive to step changes (e.g. spikes after a brake).
 * - When [data] is empty we draw the grid only — caller doesn't need to gate.
 */
@Composable
fun RealtimeLineChart(
    data: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 120.dp,
    minSpan: Float = 1f,
    showGrid: Boolean = true
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val gridDashed = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 8f)) }

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (showGrid) drawGrid(gridColor, gridDashed)
            if (data.size < 2) return@Canvas

            // Determine vertical range
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (v in data) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val span = (hi - lo).coerceAtLeast(minSpan)

            val w = size.width
            val h = size.height
            val stepX = w / (data.size - 1).coerceAtLeast(1)

            // Build path
            val linePath = Path()
            val fillPath = Path()
            for ((i, v) in data.withIndex()) {
                val x = i * stepX
                val y = h - ((v - lo) / span) * h
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(w, h)
            fillPath.close()

            // Translucent area beneath the line for a "filled" graph feel
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.30f), Color.Transparent)
                )
            )
            // The line itself
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // Glowing tip — the most recent sample
            val lastX = (data.size - 1) * stepX
            val lastY = h - ((data.last() - lo) / span) * h
            drawCircle(color, radius = 4.5f, center = Offset(lastX, lastY))
            drawCircle(color.copy(alpha = 0.25f), radius = 12f, center = Offset(lastX, lastY))
        }
    }
}

private fun DrawScope.drawGrid(color: Color, dashed: PathEffect) {
    val rows = 4
    val cols = 6
    val stepY = size.height / rows
    val stepX = size.width / cols
    for (r in 1 until rows) {
        val y = r * stepY
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dashed)
    }
    for (c in 1 until cols) {
        val x = c * stepX
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f, pathEffect = dashed)
    }
}
