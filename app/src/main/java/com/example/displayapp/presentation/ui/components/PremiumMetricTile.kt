package com.example.displayapp.presentation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.presentation.state.AlertLevel
import com.example.displayapp.presentation.ui.common.AnimatedCounter
import com.example.displayapp.ui.theme.Dim

/**
 * Telemetry tile redesigned to match the mockup.
 *
 * Layout:
 *   ┌──────────────────────────┐
 *   │ 🔋 Battery           ⓘ   │
 *   │                          │
 *   │  80.0          %         │
 *   └──────────────────────────┘
 *
 * - Icon (left, white-ish) + label (medium gray) on the top row
 * - Info dot on the right (kept decorative)
 * - Big number left-aligned with the unit suffix to its right (smaller, dim)
 * - Card is a rounded surface using the theme's surfaceVariant, with a
 *   subtle outline that works in both light & dark modes.
 */
@Composable
fun PremiumMetricTile(
    icon: ImageVector,
    label: String,
    value: Float,
    unit: String,
    modifier: Modifier = Modifier,
    level: AlertLevel = AlertLevel.NORMAL,
    fractionDigits: Int = 0
) {
    MetricTileShell(
        icon = icon,
        label = label,
        level = level,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            AnimatedCounter(
                value = value,
                fractionDigits = fractionDigits,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** String-value overload — for metrics already formatted upstream. */
@Composable
fun PremiumMetricTile(
    icon: ImageVector,
    label: String,
    valueText: String,
    unit: String,
    modifier: Modifier = Modifier,
    level: AlertLevel = AlertLevel.NORMAL
) {
    MetricTileShell(
        icon = icon,
        label = label,
        level = level,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun MetricTileShell(
    icon: ImageVector,
    label: String,
    @Suppress("UNUSED_PARAMETER") level: AlertLevel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Pin the value to the bottom of the tile: every tile in a row shares the
            // same height, so bottom-anchoring makes the numbers line up even when a
            // longer label ("Controller Temp") wraps to two lines while a shorter one
            // ("Battery Temp") stays on one. Min 14.dp keeps the label/value gap.
            Spacer(Modifier.heightIn(min = 14.dp).weight(1f))
            content()
        }
    }
}
