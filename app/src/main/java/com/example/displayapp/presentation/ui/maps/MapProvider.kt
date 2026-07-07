package com.example.displayapp.presentation.ui.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.example.displayapp.domain.model.GeoLocation

/**
 * Renderer-neutral map abstraction. The app's screens depend only on this interface
 * and the plain data types below — never on MapLibre, Google Maps, or any SDK type.
 * Swapping the renderer is a single [MapProvider] implementation change; nothing in
 * the UI, the [com.example.displayapp.data.navigation.RouteNavigator], the BLE
 * navigation protocol, or the routing layer is affected.
 *
 * The current implementation is [MapLibreMapProvider]; the default is [NoMapProvider]
 * (unconfigured), so callers gate on [isConfigured] and show a placeholder when no
 * tile style is set up.
 */
interface MapProvider {

    /** True when a tile style is configured; false → callers render a placeholder. */
    val isConfigured: Boolean

    /**
     * Render the map. All inputs are renderer-neutral: [camera] positions the view,
     * [content] supplies the location puck + a provider-agnostic route polyline (a
     * list of domain [GeoLocation] points — NOT tied to any routing SDK). [interactive]
     * toggles pan/zoom/rotate gestures (off for the cockpit mini-map, on for fullscreen).
     */
    @Composable
    fun Map(
        camera: MapCameraState,
        content: MapContent,
        modifier: Modifier,
        interactive: Boolean,
    )
}

/** Camera pose. [bearingDeg] drives heading-up rotation (0 = north-up). */
@Immutable
data class MapCameraState(
    val target: GeoLocation? = null,
    val zoom: Double = 15.0,
    val bearingDeg: Float = 0f,
    val tiltDeg: Float = 0f,
)

/**
 * What to draw on the map. [routePath] is the resolved route as domain points — the
 * overlay depends only on this, so any routing provider (GraphHopper, ORS, HERE, …)
 * or the live [com.example.displayapp.domain.model.NavProgress] stream can supply it.
 */
@Immutable
data class MapContent(
    val location: GeoLocation? = null,
    val showLocationPuck: Boolean = true,
    val routePath: List<GeoLocation> = emptyList(),
    val destination: GeoLocation? = null,
)

/** Default provider used until a real renderer is provided. Renders nothing. */
object NoMapProvider : MapProvider {
    override val isConfigured: Boolean = false

    @Composable
    override fun Map(
        camera: MapCameraState,
        content: MapContent,
        modifier: Modifier,
        interactive: Boolean,
    ) { /* no-op; callers gate on isConfigured */ }
}

/** Injects the active [MapProvider] into the composition (set once near the app root). */
val LocalMapProvider = staticCompositionLocalOf<MapProvider> { NoMapProvider }
