package com.innodrive.evdash.presentation.ui.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.presentation.ui.icons.EvIcons
import com.innodrive.evdash.presentation.viewmodel.MapsViewModel
import com.innodrive.evdash.ui.theme.EvBlue

/**
 * Drive-screen cockpit mini-map. Renderer-agnostic: it reads the active
 * [MapProvider] from [LocalMapProvider] and draws the live location + route via the
 * neutral [CockpitMap]. Shows the [MapStyleGate] placeholder until a tile style is set.
 *
 * The outer Box + floating chips render whether or not the renderer is configured, so
 * the placeholder still reads as a tappable "open navigation" entry point.
 */
@Composable
fun MiniMapCard(
    viewModel: MapsViewModel,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapProvider = LocalMapProvider.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenFullscreen),
    ) {
        MapStyleGate(configured = mapProvider.isConfigured, modifier = Modifier.fillMaxSize()) {
            CockpitMap(
                location = state.currentLocation,
                routePath = state.route?.polyline.orEmpty(),
                destination = state.destination,
                modifier = Modifier.fillMaxSize(),
                interactive = false, // cockpit glance view — no gestures
            )
        }

        MiniMapOverlay(
            locationLabel = state.currentLocation?.let {
                "%.4f, %.4f".format(it.latitude, it.longitude)
            } ?: "Acquiring GPS…",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
        OpenChip(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

/**
 * Renderer-neutral cockpit map: follows [location] with a heading-up camera and draws
 * the location puck + provider-agnostic [routePath]. Battery-conscious camera throttling
 * lives in the [MapProvider] implementation (min-distance / bearing gate).
 *
 * @param headingUp rotate the map to the travel bearing (0 = north-up).
 */
@Composable
fun CockpitMap(
    location: GeoLocation?,
    routePath: List<GeoLocation>,
    destination: GeoLocation?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    headingUp: Boolean = true,
    zoom: Double = 16.0,
    onLongPress: (GeoLocation) -> Unit = {},
    fitRoute: Boolean = false,
    fitToken: Int = 0,
    fitPadding: FitPadding = FitPadding(),
) {
    val mapProvider = LocalMapProvider.current
    if (!mapProvider.isConfigured) return

    // Fit the whole route (north-up) when asked — preview, or an Overview during nav.
    // Otherwise follow: heading-up rotates the camera to the rider's bearing.
    val camera = if (fitRoute && routePath.size >= 2) {
        MapCameraState(
            target = location ?: JAKARTA_FALLBACK,
            zoom = zoom,
            bearingDeg = 0f,
            fitBounds = routePath,
            fitToken = fitToken,
            fitPadding = fitPadding,
        )
    } else {
        MapCameraState(
            target = location ?: JAKARTA_FALLBACK,
            zoom = if (location != null) zoom else 4.0,
            bearingDeg = if (headingUp) (location?.bearingDeg ?: 0f) else 0f,
        )
    }
    mapProvider.Map(
        camera = camera,
        content = MapContent(
            location = location,
            showLocationPuck = true,
            routePath = routePath,
            destination = destination,
        ),
        modifier = modifier,
        interactive = interactive,
        onLongPress = onLongPress,
    )
}

private val JAKARTA_FALLBACK = GeoLocation(latitude = -6.2088, longitude = 106.8456)

@Composable
private fun MiniMapOverlay(locationLabel: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(EvBlue))
            Spacer(Modifier.size(0.dp))
            Text(
                text = locationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun OpenChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = EvBlue,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Navigate",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = EvIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
