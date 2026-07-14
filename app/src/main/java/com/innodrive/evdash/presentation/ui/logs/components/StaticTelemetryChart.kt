package com.innodrive.evdash.presentation.ui.logs.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.innodrive.evdash.presentation.ui.common.GlassCard

/**
 * Reusable historical telemetry chart.
 *
 * Renders [series] as a smooth line over a static dataset. Used by the
 * Trip Detail screen for the four hero charts (speed / voltage / current /
 * temperature). The smaller [MiniSparkline] reuses the same draw logic at
 * card size for inline previews on the trips list.
 *
 * Why a single Canvas + FloatArray?
 *  - No per-frame allocation: the Path is built once per draw.
 *  - No per-point composable: 600 sample points draw as one Path stroke,
 *    not 600 layout nodes.
 *  - The data is already pre-formatted by the VM — recompositions only
 *    happen when the trip changes, not per-pixel.
 */
/**
 * Reusable historical telemetry chart.
 *
 * Series values stay in canonical SI (km/h, °C, etc.); [displayConverter]
 * is applied only at the label sites (header latest, footer min/max). This
 * keeps the chart line shape unit-invariant and lets the user toggle
 * km/h↔mph in Settings without rebuilding the dataset — only the labels
 * recompose. The canvas itself draws normalized to the series' own range,
 * so converting before drawing would have no visual effect anyway.
 */
@Composable
fun StaticTelemetryChart(
    title: String,
    unit: String,
    series: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    latestFormat: String = "%.1f",
    displayConverter: (Float) -> Float = { it }
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChartHeader(
                title = title,
                unit = unit,
                series = series,
                color = color,
                latestFormat = latestFormat,
                displayConverter = displayConverter
            )
            ChartCanvas(
                series = series,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )
            ChartFooter(
                series = series,
                latestFormat = latestFormat,
                unit = unit,
                displayConverter = displayConverter
            )
        }
    }
}

@Composable
private fun ChartHeader(
    title: String,
    unit: String,
    series: FloatArray,
    color: Color,
    latestFormat: String,
    displayConverter: (Float) -> Float
) {
    val (min, max, last) = remember(series) { quickStats(series) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = if (last.isNaN()) "—" else "${latestFormat.format(displayConverter(last))} $unit",
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
    }
    // Suppressed-but-readable min/max chip row could go here in the future
    @Suppress("UNUSED_VARIABLE") val unused = min to max
}

@Composable
private fun ChartCanvas(
    series: FloatArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fillBrush = Brush.verticalGradient(
        listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.0f))
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Horizontal grid — 3 dashed reference lines
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 1..3) {
            val y = h * (i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
                pathEffect = dash
            )
        }

        if (series.size < 2) return@Canvas

        // Find min/max to normalize
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in series) {
            if (v < min) min = v
            if (v > max) max = v
        }
        // Guard against flat-line datasets so we don't divide by zero
        val span = (max - min).takeIf { it > 0.0001f } ?: 1f
        val n = series.size
        val xStep = w / (n - 1)

        // Build a single Path for the line + a filled Path for the gradient
        val linePath = Path()
        val fillPath = Path()
        for (i in 0 until n) {
            val x = i * xStep
            val y = h - ((series[i] - min) / span) * h
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        // Close the fill path along the baseline
        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(path = fillPath, brush = fillBrush)
        drawPath(
            path = linePath,
            color = color,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ChartFooter(
    series: FloatArray,
    latestFormat: String,
    unit: String,
    displayConverter: (Float) -> Float
) {
    val stats = remember(series) { quickStats(series) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Stat(label = "min", value = if (series.isEmpty()) "—" else "${latestFormat.format(displayConverter(stats.min))} $unit")
        Stat(label = "max", value = if (series.isEmpty()) "—" else "${latestFormat.format(displayConverter(stats.max))} $unit")
        Stat(label = "samples", value = series.size.toString())
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Inline sparkline used in the trips list. No card chrome, no axes — just a
 * single colored line to suggest the speed shape of the trip.
 */
@Composable
fun MiniSparkline(
    series: FloatArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (series.size < 2) return@Canvas
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (v in series) {
                if (v < min) min = v
                if (v > max) max = v
            }
            val span = (max - min).takeIf { it > 0.0001f } ?: 1f
            val n = series.size
            val xStep = size.width / (n - 1)
            val padTop = size.height * 0.15f
            val drawH = size.height - padTop * 2

            val path = Path()
            for (i in 0 until n) {
                val x = i * xStep
                val y = padTop + drawH - ((series[i] - min) / span) * drawH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2f, cap = StrokeCap.Round)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Helpers                                                                   */
/* -------------------------------------------------------------------------- */

// Data classes auto-generate componentN — no manual destructuring needed.
private data class QuickStats(val min: Float, val max: Float, val last: Float)

private fun quickStats(series: FloatArray): QuickStats {
    if (series.isEmpty()) return QuickStats(0f, 0f, Float.NaN)
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    for (v in series) {
        if (v < min) min = v
        if (v > max) max = v
    }
    return QuickStats(min, max, series.last())
}
