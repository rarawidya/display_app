package com.example.displayapp.presentation.ui.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.BuildConfig
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvBlueDeep

/**
 * Renders [content] only when a Google Maps API key is configured. Otherwise
 * shows a polished "map preview" placeholder — stylized roads, a marker pin,
 * and a friendly "awaiting API key" caption — so the surface still feels like
 * a map area while the user finishes setup.
 *
 * To activate the real map, add the following to `local.properties` (which is
 * gitignored) and rebuild:
 *
 *     MAPS_API_KEY=AIza…your-key…
 */
@Composable
fun MapKeyGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MapPreviewPlaceholder(modifier = modifier)
    } else {
        content()
    }
}

@Composable
private fun MapPreviewPlaceholder(modifier: Modifier = Modifier) {
    val baseTop = EvBlueDeep.copy(alpha = 0.10f)
    val baseBottom = EvBlue.copy(alpha = 0.04f)
    val roadColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val roadDashColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1) Faux map basemap — vertical gradient + decorative roads
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(baseTop, baseBottom))
                    )
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Main horizontal "highway" through the middle
                drawLine(
                    color = roadColor,
                    start = Offset(0f, h * 0.55f),
                    end = Offset(w, h * 0.55f),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round
                )
                // Secondary diagonal road
                drawLine(
                    color = roadColor,
                    start = Offset(w * 0.05f, h * 0.20f),
                    end = Offset(w * 0.75f, h * 0.85f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
                // Vertical side road
                drawLine(
                    color = roadColor,
                    start = Offset(w * 0.75f, 0f),
                    end = Offset(w * 0.75f, h),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                // Dashed minor road
                drawLine(
                    color = roadDashColor,
                    start = Offset(w * 0.20f, h * 0.85f),
                    end = Offset(w * 0.95f, h * 0.30f),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
                drawLine(
                    color = roadDashColor,
                    start = Offset(0f, h * 0.20f),
                    end = Offset(w * 0.55f, h * 0.20f),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }

            // 2) Marker pin + caption
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MarkerPin()
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Map preview",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Waiting for MAPS_API_KEY",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MarkerPin() {
    // Outer halo + inner dot — same visual language as a real map marker but
    // drawn as plain composables so it tints with the theme.
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(EvBlue.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(EvBlue)
        )
    }
    // Tail / shadow dot underneath the halo to suggest a pin
    Spacer(Modifier.padding(top = 2.dp))
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.18f))
    )
}
