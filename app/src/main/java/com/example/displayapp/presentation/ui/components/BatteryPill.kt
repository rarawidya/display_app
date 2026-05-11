package com.example.displayapp.presentation.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.displayapp.presentation.state.AlertLevel
import com.example.displayapp.presentation.state.batteryAlertLevel
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvRed

/**
 * Horizontal premium battery pill — modern EV style.
 *
 * Design:
 * - Long capsule with internal accent fill, gradient end-to-end (matches the
 *   "energy moving" feeling typical of high-end EV dashes)
 * - Lightning bolt icon swaps to a charging-connected indicator when [charging]
 * - Spring-animated fill so the bar glides instead of snapping
 *
 * Color tracks AlertLevel: green (>50%), amber (>20%), red (≤20%).
 */
@Composable
fun BatteryPill(
    percent: Int,
    modifier: Modifier = Modifier,
    charging: Boolean = false
) {
    val level = batteryAlertLevel(percent)
    val color = when (level) {
        AlertLevel.NORMAL   -> EvLime
        AlertLevel.WARNING  -> EvAmber
        AlertLevel.CRITICAL -> EvRed
    }
    val animatedColor by animateColorAsState(targetValue = color, label = "battColor")
    val animatedFraction by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100) / 100f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "battFill"
    )

    GlassCard(modifier = modifier, accent = if (level != AlertLevel.NORMAL) color else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = EvIcons.Bolt,
                        contentDescription = null,
                        tint = animatedColor,
                        modifier = Modifier
                            .padding(end = Dim.xs)
                            .height(18.dp)
                    )
                    Text(
                        text = if (charging) "CHARGING" else "BATTERY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleLarge,
                    color = animatedColor
                )
            }

            // Capsule track + fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(animatedColor.copy(alpha = 0.7f), animatedColor)
                            )
                        )
                )
                // Tick marks for 25/50/75
                Row(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }
    }
}
