package com.innodrive.evdash.presentation.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Smoothly interpolates a numeric readout instead of jumping between integer ticks.
 *
 * Benefits over plain Text(value.toString()):
 * - No jitter on noisy telemetry: spring damping smooths out small fluctuations
 * - Sub-second latency masked: a 200ms spring catches up to the new sample
 *   before the eye notices the gap
 *
 * Cost: a single Float animation per counter (~negligible).
 *
 * Caller chooses fractionDigits — 0 for integers (speed), 1 for tenths (km, V),
 * 2 for hundredths (current).
 */
@Composable
fun AnimatedCounter(
    value: Float,
    modifier: Modifier = Modifier,
    fractionDigits: Int = 0,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = LocalContentColor.current
) {
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "counter"
    )
    val format = if (fractionDigits == 0) "%.0f" else "%.${fractionDigits}f"
    Text(
        text = format.format(animated),
        style = style,
        color = color,
        modifier = modifier
    )
}
