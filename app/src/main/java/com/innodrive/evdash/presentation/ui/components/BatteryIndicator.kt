package com.innodrive.evdash.presentation.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.innodrive.evdash.presentation.state.AlertLevel
import com.innodrive.evdash.presentation.state.batteryAlertLevel

/**
 * Circular ring-style battery indicator.
 * Uses a 270-degree arc that fills proportional to battery percentage.
 * Color transitions: green → yellow → red based on charge level.
 */
@Composable
fun BatteryIndicator(
    percent: Int,
    modifier: Modifier = Modifier
) {
    val animatedPercent by animateFloatAsState(
        targetValue = percent.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "battery"
    )

    val color = when (batteryAlertLevel(percent)) {
        AlertLevel.NORMAL -> Color(0xFF00E676)
        AlertLevel.WARNING -> Color(0xFFFFD600)
        AlertLevel.CRITICAL -> Color(0xFFFF1744)
    }

    val bgColor = Color(0xFF1E1E1E)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val strokeWidth = 8.dp.toPx()
                val arcSize = Size(
                    size.width - strokeWidth,
                    size.height - strokeWidth
                )
                val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                val sweepAngle = 270f
                val startAngle = 135f

                // Background ring
                drawArc(
                    color = bgColor,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Filled ring
                val fillSweep = sweepAngle * (animatedPercent / 100f).coerceIn(0f, 1f)
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = fillSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Percentage text inside ring
            Text(
                text = "$percent%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }

        Text(
            text = "Battery",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
