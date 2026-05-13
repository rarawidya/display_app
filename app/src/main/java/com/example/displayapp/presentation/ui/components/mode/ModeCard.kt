package com.example.displayapp.presentation.ui.components.mode

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.domain.model.VehicleMode
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.GlassStroke
import com.example.displayapp.ui.theme.GlassTint

/**
 * Premium drive-mode card: rounded automotive surface housing a segmented
 * selector for the five [VehicleMode]s.
 *
 * Layout:
 *
 *   ┌───────────────────────────────────────────────────────────┐
 *   │  DRIVE MODE                                  ● cyan glow   │
 *   │  ╭────────────────────────────────────────────────────╮    │
 *   │  │  P  │  E  │  N  │  S  │  ▣ REGEN ▣                 │    │
 *   │  ╰────────────────────────────────────────────────────╯    │
 *   │  Energy recovery active                                    │
 *   └───────────────────────────────────────────────────────────┘
 *
 * The active segment expands (`weight 1.6f`), grows a tinted fill, animates
 * its accent and pulses a soft halo. PARK has catalog intensity 0 so the
 * pulse collapses to a static neutral border — it never visually competes
 * with an actively-driving mode.
 *
 * Behavior text crossfades on every mode change via [AnimatedContent].
 *
 * No internal state: `mode` flows in from a [androidx.lifecycle.ViewModel]
 * `StateFlow`. `onModeClick` is opt-in — supply it when the surface should
 * accept input (settings/dev tool); omit it for read-only display in the
 * live cockpit.
 */
@Composable
fun ModeCard(
    mode: VehicleMode,
    modifier: Modifier = Modifier,
    onModeClick: ((VehicleMode) -> Unit)? = null
) {
    val visuals = mode.visuals()
    val animatedAccent by animateColorAsState(
        targetValue = visuals.accent,
        animationSpec = tween(durationMillis = 420),
        label = "modeAccent"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, animatedAccent.copy(alpha = 0.35f))
    ) {
        Box(
            Modifier
                .background(
                    Brush.verticalGradient(
                        0f to animatedAccent.copy(alpha = 0.12f * visuals.intensity),
                        0.55f to GlassTint,
                        1f to Color.Transparent
                    )
                )
                .padding(horizontal = Dim.lg, vertical = Dim.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header(visuals.label, animatedAccent, visuals.intensity)
                ModeSegmentedSelector(
                    activeMode = mode,
                    accent = animatedAccent,
                    onModeClick = onModeClick
                )
                BehaviorText(visuals.behavior, animatedAccent)
            }
        }
    }
}

@Composable
private fun Header(label: String, accent: Color, intensity: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "DRIVE MODE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PulsingDot(color = accent, intensity = intensity)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
private fun ModeSegmentedSelector(
    activeMode: VehicleMode,
    accent: Color,
    onModeClick: ((VehicleMode) -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEGMENT_HEIGHT),
        shape = RoundedCornerShape(SEGMENT_HEIGHT / 2),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, GlassStroke)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VehicleMode.entries.forEach { mode ->
                val isActive = mode == activeMode
                val v = mode.visuals()
                ModeSegment(
                    visuals = v,
                    isActive = isActive,
                    accent = if (isActive) accent else v.accent,
                    onClick = onModeClick?.let { cb -> { cb(mode) } }
                )
            }
        }
    }
}

@Composable
private fun RowScope.ModeSegment(
    visuals: ModeVisuals,
    isActive: Boolean,
    accent: Color,
    onClick: (() -> Unit)?
) {
    // Smooth animated transitions across every visual axis.
    val animatedFill by animateColorAsState(
        targetValue = if (isActive) accent.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(durationMillis = 360),
        label = "segmentFill"
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (isActive) accent.copy(alpha = 0.85f) else Color.Transparent,
        animationSpec = tween(durationMillis = 360),
        label = "segmentBorder"
    )
    val animatedLabelColor by animateColorAsState(
        targetValue = if (isActive)
            accent
        else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 360),
        label = "segmentLabel"
    )
    val pulse = pulseAlpha(active = isActive, intensity = visuals.intensity)
    val animatedHalo by animateFloatAsState(
        targetValue = if (isActive) pulse else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "segmentHalo"
    )

    val shape = RoundedCornerShape(SEGMENT_HEIGHT / 2)
    Surface(
        modifier = Modifier
            .weight(if (isActive) ACTIVE_WEIGHT else INACTIVE_WEIGHT)
            .height(SEGMENT_INNER_HEIGHT)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(if (isActive) 1.2.dp else 0.5.dp, animatedBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Outer halo behind the fill — drives the breathing glow.
                .background(accent.copy(alpha = animatedHalo * 0.18f), shape)
                .background(animatedFill, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                // Long label when active for quick glance recognition; short
                // letter otherwise so all five modes fit on a phone width
                // without truncation.
                text = if (isActive) visuals.label else visuals.short,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                color = animatedLabelColor,
                letterSpacing = if (isActive) 0.8.sp else 0.4.sp
            )
        }
    }
}

@Composable
private fun BehaviorText(text: String, accent: Color) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(180)) },
        label = "modeBehavior"
    ) { current ->
        Text(
            text = current,
            style = MaterialTheme.typography.bodySmall,
            color = accent.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PulsingDot(color: Color, intensity: Float) {
    val alpha = pulseAlpha(active = true, intensity = intensity)
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Breathing alpha for active glows. PARK collapses to a static low value
 * (intensity == 0). Higher-intensity modes (REGEN, SPORT == 1.0f) breathe
 * across the full range — they should attract the eye while driving.
 */
@Composable
private fun pulseAlpha(active: Boolean, intensity: Float): Float {
    if (!active || intensity <= 0f) return PULSE_MIN
    val transition = rememberInfiniteTransition(label = "modePulse")
    val v by transition.animateFloat(
        initialValue = PULSE_MIN,
        targetValue = PULSE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "modePulseValue"
    )
    return PULSE_MIN + (v - PULSE_MIN) * intensity
}

private val SEGMENT_HEIGHT = 44.dp
private val SEGMENT_INNER_HEIGHT = 36.dp
private const val ACTIVE_WEIGHT = 1.6f
private const val INACTIVE_WEIGHT = 1f
private const val PULSE_MIN = 0.35f
private const val PULSE_MAX = 1f
