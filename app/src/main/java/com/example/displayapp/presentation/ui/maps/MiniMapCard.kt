package com.example.displayapp.presentation.ui.maps

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.ui.theme.EvBlue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Embedded mini-map for the Drive screen.
 *
 * Architecture note: the [MapKeyGate] wraps only the map content — the outer
 * Box and the floating chips (location label, "Navigate" pill) render whether
 * or not the API key is configured. That way the placeholder still reads as a
 * tappable navigation entry point during dev.
 */
@Composable
fun MiniMapCard(
    viewModel: MapsViewModel,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenFullscreen)
    ) {
        // Map area — real GoogleMap when keyed, stylized placeholder otherwise.
        MapKeyGate(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 2.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    val fallback = LatLng(-6.2088, 106.8456) // Jakarta default
                    val cameraPosition: CameraPositionState = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(fallback, 4f)
                    }
                    LaunchedEffect(state.currentLocation) {
                        val loc = state.currentLocation ?: return@LaunchedEffect
                        cameraPosition.animate(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(loc.latitude, loc.longitude),
                                15f
                            )
                        )
                    }
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPosition,
                        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
                        properties = MapProperties(
                            mapType = MapType.NORMAL,
                            isMyLocationEnabled = false
                        ),
                        uiSettings = MapUiSettings(
                            compassEnabled = false,
                            zoomControlsEnabled = false,
                            scrollGesturesEnabled = false,
                            zoomGesturesEnabled = false,
                            rotationGesturesEnabled = false,
                            tiltGesturesEnabled = false,
                            myLocationButtonEnabled = false,
                            mapToolbarEnabled = false
                        )
                    ) {
                        state.currentLocation?.let { loc ->
                            Marker(state = MarkerState(LatLng(loc.latitude, loc.longitude)))
                        }
                    }
                }
            }
        }

        // Floating chips — render over whatever's behind (real map or placeholder)
        MiniMapOverlay(
            locationLabel = state.currentLocation?.let {
                "%.4f, %.4f".format(it.latitude, it.longitude)
            } ?: "Acquiring GPS…",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        )
        OpenChip(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        )
    }
}

@Composable
private fun MiniMapOverlay(locationLabel: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(EvBlue)
            )
            Spacer(Modifier.size(0.dp))
            Text(
                text = locationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
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
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Navigate",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = EvIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
