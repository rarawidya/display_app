package com.example.displayapp.presentation.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.presentation.state.AlertLevel
import com.example.displayapp.presentation.state.batteryAlertLevel
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed

/**
 * Hero battery card — sits between the gauge and the metric grid.
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ 🔋 Battery                            80 %   │
 *   │ ██████████████████░░░░░░░░░░ (animated bar)  │
 *   └──────────────────────────────────────────────┘
 *
 * - Percentage and bar fill animate independently from telemetry noise via
 *   [animateFloatAsState], so they read as smooth instead of jittery at 20 Hz.
 * - Fill color follows [AlertLevel]: blue/green for healthy, amber on warning,
 *   red when critical — drivers parse the color cue peripherally.
 * - Only the SoC (`batteryPercent`, a real wire field) is shown; a range
 *   estimate was removed because the controller provides no range channel.
 */
@Composable
fun BatteryRowCard(
    batteryPercent: Int,
    known: Boolean = true,
    charging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val clamped = batteryPercent.coerceIn(0, 100)
    val level = batteryAlertLevel(clamped)
    val fillTarget = when (level) {
        AlertLevel.NORMAL   -> if (clamped > 70) EvGreen else EvBlue
        AlertLevel.WARNING  -> EvAmber
        AlertLevel.CRITICAL -> EvRed
    }
    val fillColor by animateColorAsState(targetValue = fillTarget, label = "batteryFill")
    val animatedFraction by animateFloatAsState(
        targetValue = clamped / 100f,
        label = "batteryFraction"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: icon + label + percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = EvIcons.Battery,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Battery",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Charging pill — pack current ≥ 1 A into the battery (with
                    // hysteresis in the ViewModel so it doesn't flicker).
                    if (charging) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(EvGreen.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = EvIcons.Bolt,
                                contentDescription = "Charging",
                                tint = EvGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Charging",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = EvGreen
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // SoC unknown (wire sends 255 for ~1 s after board boot) → "—".
                    Text(
                        text = if (known) "$clamped" else "—",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (known) {
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }

            // Progress bar — animated, color-coded
            BatteryProgressBar(
                fraction = animatedFraction,
                color = fillColor
            )
        }
    }
}

@Composable
private fun BatteryProgressBar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        // Animated fill — width is fractional. Spacer + Box keeps allocations zero.
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.size(0.dp))  // anchor for the layout sibling above
    }
}
