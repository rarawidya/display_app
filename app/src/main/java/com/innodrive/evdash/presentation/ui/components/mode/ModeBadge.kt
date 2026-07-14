package com.innodrive.evdash.presentation.ui.components.mode

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.innodrive.evdash.domain.model.VehicleMode

/**
 * Compact pill showing the currently-active [VehicleMode]. Used in the
 * Charts header, Trip Detail hero card, and the Logs page header so the
 * mode reads identically on every cockpit surface.
 *
 * Shares the [ModeVisuals] catalog with [ModeCard], so a future palette
 * change touches one file.
 */
@Composable
fun ModeBadge(
    mode: VehicleMode,
    modifier: Modifier = Modifier
) {
    val visuals = mode.visuals()
    val accent by animateColorAsState(
        targetValue = visuals.accent,
        animationSpec = tween(durationMillis = 360),
        label = "badgeAccent"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .background(accent.copy(alpha = 0.12f * visuals.intensity.coerceAtLeast(0.25f)))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = visuals.label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp
            )
        }
    }
}
