package com.example.displayapp.presentation.ui.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.displayapp.domain.model.GeoLocation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [MapProvider] backed by MapLibre GL (open-source, OpenStreetMap-friendly). Renders
 * whatever vector/raster [styleUrl] points at (MapTiler/Stadia/Protomaps/self-hosted);
 * when [styleUrl] is blank the provider reports [isConfigured] = false and callers show
 * the [MapStyleGate] placeholder instead of an empty map.
 *
 * The route line and location puck are drawn as runtime GeoJSON sources + style layers
 * (core SDK only — no annotation plugin). Heading-up is achieved by rotating the camera
 * (bearing), so the puck circle stays upright and centered. Camera moves are throttled
 * (min-distance / bearing gate) to stay battery-friendly on the always-on Drive screen.
 */
class MapLibreMapProvider(private val styleUrl: String) : MapProvider {

    override val isConfigured: Boolean get() = styleUrl.isNotBlank()

    @Composable
    override fun Map(
        camera: MapCameraState,
        content: MapContent,
        modifier: Modifier,
        interactive: Boolean,
    ) {
        if (!isConfigured) return

        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val mapView = remember { MapView(context).apply { onCreate(null) } }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        // Last camera target actually applied — the battery-conscious move gate.
        val lastApplied = remember { mutableStateOf<Pair<GeoLocation, Float>?>(null) }

        // Acquire the map + load style + install the route/puck sources & layers once.
        DisposableEffect(Unit) {
            mapView.getMapAsync { m ->
                m.uiSettings.apply {
                    isCompassEnabled = false
                    isLogoEnabled = false
                    isAttributionEnabled = false
                    setAllGesturesEnabled(interactive)
                }
                m.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    style.addSource(GeoJsonSource(ROUTE_SRC))
                    style.addLayer(
                        LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                            PropertyFactory.lineColor(ROUTE_COLOR),
                            PropertyFactory.lineWidth(6f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    style.addSource(GeoJsonSource(PUCK_SRC))
                    style.addLayer(
                        CircleLayer(PUCK_LAYER, PUCK_SRC).withProperties(
                            PropertyFactory.circleColor(PUCK_COLOR),
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(3f),
                        ),
                    )
                    map = m
                }
            }
            onDispose { }
        }

        // Forward the composition lifecycle to the MapView (MapLibre requires this).
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                mapView.onStop()
                mapView.onDestroy()
            }
        }

        // Push camera + overlays whenever the neutral inputs change.
        LaunchedEffect(camera, content, map) {
            val m = map ?: return@LaunchedEffect
            m.uiSettings.setAllGesturesEnabled(interactive)

            camera.target?.let { target ->
                val prev = lastApplied.value
                val moved = prev == null ||
                    distanceMeters(prev.first, target) >= MIN_CAMERA_MOVE_M ||
                    kotlin.math.abs(prev.second - camera.bearingDeg) >= MIN_BEARING_DELTA
                if (moved) {
                    lastApplied.value = target to camera.bearingDeg
                    val pos = CameraPosition.Builder()
                        .target(LatLng(target.latitude, target.longitude))
                        .zoom(camera.zoom)
                        .bearing(camera.bearingDeg.toDouble())
                        .tilt(camera.tiltDeg.toDouble())
                        .build()
                    m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), CAMERA_ANIM_MS)
                }
            }

            // Route line (provider-agnostic list of points → LineString). Both
            // branches yield a FeatureCollection so setGeoJson resolves unambiguously.
            val routeFc = if (content.routePath.size >= 2) {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        LineString.fromLngLats(content.routePath.map { it.toPoint() }),
                    ),
                )
            } else {
                FeatureCollection.fromFeatures(emptyArray<Feature>())
            }
            m.style?.getSourceAs<GeoJsonSource>(ROUTE_SRC)?.setGeoJson(routeFc)

            // Location puck.
            val puckFc = if (content.showLocationPuck && content.location != null) {
                FeatureCollection.fromFeature(Feature.fromGeometry(content.location.toPoint()))
            } else {
                FeatureCollection.fromFeatures(emptyArray<Feature>())
            }
            m.style?.getSourceAs<GeoJsonSource>(PUCK_SRC)?.setGeoJson(puckFc)
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }

    private fun GeoLocation.toPoint(): Point = Point.fromLngLat(longitude, latitude)

    private companion object {
        const val ROUTE_SRC = "route-src"
        const val ROUTE_LAYER = "route-layer"
        const val PUCK_SRC = "puck-src"
        const val PUCK_LAYER = "puck-layer"
        const val ROUTE_COLOR = "#2563EB"
        const val PUCK_COLOR = "#3B82F6"
        const val CAMERA_ANIM_MS = 700
        const val MIN_CAMERA_MOVE_M = 8.0   // don't re-center for sub-8 m GPS jitter
        const val MIN_BEARING_DELTA = 4f    // …or sub-4° heading wobble
    }
}

private fun distanceMeters(a: GeoLocation, b: GeoLocation): Double {
    val r = 6_371_000.0
    val dLat = (b.latitude - a.latitude) * Math.PI / 180
    val dLng = (b.longitude - a.longitude) * Math.PI / 180
    val s1 = sin(dLat / 2)
    val s2 = sin(dLng / 2)
    val h = s1 * s1 + cos(a.latitude * Math.PI / 180) * cos(b.latitude * Math.PI / 180) * s2 * s2
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
