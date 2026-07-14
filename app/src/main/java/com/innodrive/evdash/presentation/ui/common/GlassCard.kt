package com.innodrive.evdash.presentation.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.innodrive.evdash.ui.theme.Dim
import com.innodrive.evdash.ui.theme.GlassStroke
import com.innodrive.evdash.ui.theme.GlassTint

/**
 * "Glassmorphic" card.
 *
 * - Translucent surface tinted with a subtle white wash for the carbon palette
 * - Faint top→bottom highlight to suggest a glossy panel under cockpit lighting
 * - 1dp inner stroke that catches dark borders so cards remain visible on near-black bg
 * - No real-time blur (would harm framerate during 20Hz telemetry); the visual is
 *   approximated with tint + gradient + stroke instead
 *
 * Use [interactive] = true when the card responds to taps (slightly stronger tint).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Dim.cardCorner,
    contentPadding: Dp = Dim.lg,
    interactive: Boolean = false,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val baseSurface = MaterialTheme.colorScheme.surfaceVariant
    val tint = if (interactive) GlassTint.copy(alpha = 0.16f) else GlassTint
    val borderColor = accent?.copy(alpha = 0.45f) ?: GlassStroke

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = baseSurface,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.verticalGradient(
                        listOf(tint, Color.Transparent)
                    )
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}
