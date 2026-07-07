package com.example.displayapp.presentation.ui.maps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.domain.model.GeoPlace
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen

/**
 * Fullscreen navigation surface.
 *
 * Layout (overlay on top of the interactive map):
 *   - top: back button + search bar
 *   - bottom: ETA / distance / battery card (when a route is active)
 *   - center: GoogleMap with current-location marker + (optional) route polyline
 *
 * Architecture:
 * - Shares the singleton [MapsViewModel] with the Drive screen, so destination
 *   picks and live location are observed in one place.
 * - "Search results" today are a canned list of demo places — wire to Places
 *   Autocomplete here when billing is set up. The data flow through the VM
 *   already matches what Places would feed.
 */
@Composable
fun NavigationScreen(
    viewModel: MapsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            // Map surface — renderer-agnostic. When no tile style is configured
            // the gate renders a "map preview" placeholder in place of the live
            // map. The search bar, back button, and ETA card above stay live
            // regardless, so the user can still browse and select destinations.
            val mapProvider = LocalMapProvider.current
            MapStyleGate(configured = mapProvider.isConfigured, modifier = Modifier.fillMaxSize()) {
                CockpitMap(
                    location = state.currentLocation,
                    routePath = state.route?.polyline.orEmpty(),
                    destination = state.destination,
                    modifier = Modifier.fillMaxSize(),
                    interactive = true, // fullscreen browse — pan/zoom on
                    headingUp = false,  // north-up for destination browsing
                    onLongPress = viewModel::dropPin, // long-press anywhere → destination
                )
            }

            // Top overlay — back button + search
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Dim.screenGutter, vertical = Dim.sm),
                verticalArrangement = Arrangement.spacedBy(Dim.sm)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dim.sm)
                ) {
                    BackPill(onClick = onBack)
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Real geocoded results appear when the query is non-empty and no
                // route is active. Picking one starts the navigation session. Or
                // long-press the map to drop a pin anywhere (hint below).
                AnimatedVisibility(
                    visible = state.searchQuery.isNotBlank() && state.route == null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    SuggestionList(
                        results = results,
                        onPick = viewModel::selectPlace
                    )
                }
            }

            // Hint: long-press to drop a pin (shown when idle, no query/route).
            AnimatedVisibility(
                visible = state.searchQuery.isBlank() && state.route == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LongPressHint(modifier = Modifier.padding(Dim.screenGutter))
            }

            // Bottom overlay — ETA / distance / battery
            AnimatedVisibility(
                visible = state.route != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit  = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                EtaCard(
                    eta = state.etaLabel.orEmpty(),
                    distance = state.distanceLabel.orEmpty(),
                    onCancel = viewModel::clearRoute,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dim.screenGutter)
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Overlay UI                                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun BackPill(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = EvIcons.ArrowBack,
                contentDescription = "Back to Drive",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = EvIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(EvBlue),
                    textStyle = TextStyle(
                        color = onSurface,
                        fontSize = 15.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                if (query.isEmpty()) {
                    Text(
                        text = "Search destination…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(
    results: List<GeoPlace>,
    onPick: (GeoPlace) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            if (results.isEmpty()) {
                items(1) {
                    Text(
                        text = "No matches — or long-press the map to drop a pin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(results, key = { "${it.name}|${it.location.latitude},${it.location.longitude}" }) { place ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(place) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(EvBlue.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = EvIcons.ChevronRight,
                                contentDescription = null,
                                tint = EvBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = place.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            if (place.detail.isNotBlank()) {
                                Text(
                                    text = place.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Idle hint: tell the user they can long-press the map to drop a destination. */
@Composable
private fun LongPressHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 4.dp
    ) {
        Text(
            text = "Long-press the map to drop a destination",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun EtaCard(
    eta: String,
    distance: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EtaMetric(label = "ETA", value = eta, accent = EvBlue)
                EtaMetric(label = "Distance", value = distance, accent = EvBlue)
                EtaMetric(label = "Battery", value = "—%", accent = EvGreen, suffix = "est")
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "Cancel route",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EtaMetric(label: String, value: String, accent: Color, suffix: String? = null) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = value.ifBlank { "—" },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

