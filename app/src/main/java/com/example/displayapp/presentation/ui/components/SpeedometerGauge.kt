package com.example.displayapp.presentation.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.ui.theme.GaugeMagenta
import com.example.displayapp.ui.theme.GaugePink
import com.example.displayapp.ui.theme.GaugePurple
import com.example.displayapp.ui.theme.GaugeTrack
import com.example.displayapp.ui.theme.GaugeTrackLight
import com.example.displayapp.ui.theme.GaugeViolet
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hero gauge — circular (270° sweep) with a violet→magenta→pink gradient
 * progress arc and a contrasting trailing track.
 *
 *   ┌───────────────────────────┐
 *   │     ▔▔▔▔▔ progress ▔▔▔    │
 *   │   ╱                   ╲   │
 *   │   ┊  6,000             ┊  │  heroValue (e.g. RPM)
 *   │   ┊  RPM               ┊  │  heroUnit
 *   │   ┊      60 KM/H       ┊  │  secondaryText (optional)
 *   │    ╲_________________ ╱   │
 *   └───────────────────────────┘
 *
 * Progress arc is driven by [progressFraction] (0..1) — decoupled from the
 * displayed [heroValue] so callers can show e.g. RPM in the center while the
 * arc tracks vehicle speed.
 */
@Composable
fun SpeedometerGauge(
    heroValue: Int,
    heroUnit: String,
    progressFraction: Float,
    secondaryText: String? = null,
    modifier: Modifier = Modifier
) {
    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "gaugeFraction"
    )

    val track = if (isSystemInDarkTheme()) GaugeTrack else GaugeTrackLight
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val numberColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 270° sweep — three-quarter circle with a 90° gap at the bottom (45° on each side of 6 o'clock).
    val startAngle = 135f
    val sweepAngle = 270f

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val strokeWidth = size.width * 0.082f
            val outerRadius = (size.minDimension - strokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcRect = Rect(
                offset = Offset(center.x - outerRadius, center.y - outerRadius),
                size = Size(outerRadius * 2, outerRadius * 2)
            )

            // 1) Background track (full sweep) — sits behind the progress
            drawArc(
                color = track,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = arcRect.topLeft,
                size = arcRect.size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2) Tick marks — sharper: longer, bolder, more contrast.
            val tickInner = outerRadius - strokeWidth * 1.45f
            val tickOuter = outerRadius - strokeWidth * 0.6f
            val tickCount = 40   // ticks across the 270° sweep
            for (i in 0..tickCount) {
                val t = i.toFloat() / tickCount
                val angleDeg = startAngle + sweepAngle * t
                val rad = Math.toRadians(angleDeg.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val isMajor = i % 5 == 0
                drawLine(
                    color = if (isMajor) tickColor else tickColor.copy(alpha = 0.55f),
                    start = Offset(center.x + tickInner * c, center.y + tickInner * s),
                    end = Offset(center.x + tickOuter * c, center.y + tickOuter * s),
                    strokeWidth = if (isMajor) 4f else 2.2f,
                    cap = StrokeCap.Square
                )
            }

            // 3) Progress arc — sweep-gradient brush clipped to the active segment
            if (animatedFraction > 0f) {
                val progressSweep = sweepAngle * animatedFraction
                val progressBrush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to GaugeViolet,
                        0.40f to GaugePurple,
                        0.75f to GaugeMagenta,
                        1.00f to GaugePink
                    ),
                    center = center
                )
                val progressPath = Path().apply {
                    arcTo(
                        rect = arcRect,
                        startAngleDegrees = startAngle,
                        sweepAngleDegrees = progressSweep,
                        forceMoveTo = true
                    )
                }
                drawPath(
                    path = progressPath,
                    brush = progressBrush,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Hero readout overlay — centered in the ring
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formatNumber(heroValue),
                fontSize = 60.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                color = numberColor,
                lineHeight = 64.sp
            )
            Text(
                text = heroUnit,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                letterSpacing = 2.sp
            )
            if (!secondaryText.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = secondaryText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = numberColor,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

private fun formatNumber(value: Int): String =
    if (value >= 1000) "%,d".format(value) else value.toString()
