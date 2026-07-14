package com.innodrive.evdash.presentation.ui.maps

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
import com.innodrive.evdash.ui.theme.EvBlue
import com.innodrive.evdash.ui.theme.EvBlueDeep

/**
 * Renders [content] only when the map renderer is [configured] (a tile style is set).
 * Otherwise shows a polished "map preview" placeholder — stylized roads, a marker pin,
 * and a caption — so the surface still reads as a map area during setup.
 *
 * Renderer-neutral: it takes a boolean rather than reading any SDK/BuildConfig, so it
 * works for MapLibre or any future [MapProvider]. Callers pass `mapProvider.isConfigured`.
 *
 * To activate the real map, add a MapLibre style URL to `local.properties` (gitignored)
 * and rebuild:
 *
 *     MAP_STYLE_URL=https://…/style.json?key=…
 */
@Composable
fun MapStyleGate(
    configured: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!configured) {
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
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(baseTop, baseBottom))),
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawLine(roadColor, Offset(0f, h * 0.55f), Offset(w, h * 0.55f), 6f, StrokeCap.Round)
                drawLine(roadColor, Offset(w * 0.05f, h * 0.20f), Offset(w * 0.75f, h * 0.85f), 4f, StrokeCap.Round)
                drawLine(roadColor, Offset(w * 0.75f, 0f), Offset(w * 0.75f, h), 3f, StrokeCap.Round)
                drawLine(
                    roadDashColor, Offset(w * 0.20f, h * 0.85f), Offset(w * 0.95f, h * 0.30f), 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
                drawLine(
                    roadDashColor, Offset(0f, h * 0.20f), Offset(w * 0.55f, h * 0.20f), 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MarkerPin()
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Map preview",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Waiting for MAP_STYLE_URL",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MarkerPin() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(EvBlue.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(EvBlue),
        )
    }
    Spacer(Modifier.padding(top = 2.dp))
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.18f)),
    )
}
