package com.example.displayapp.presentation.ui.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import com.example.displayapp.domain.model.GeoLocation
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
        onLongPress: (GeoLocation) -> Unit,
    ) {
        if (!isConfigured) return

        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val mapView = remember { MapView(context).apply { onCreate(null) } }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        // Keep the long-press handler current without re-installing the listener.
        val longPress = rememberUpdatedState(onLongPress)
        // Last camera target actually applied — the battery-conscious move gate.
        val lastApplied = remember { mutableStateOf<Pair<GeoLocation, Float>?>(null) }
        // Last fit token applied — a fit runs once per token bump (new destination /
        // Overview), never on plain recomposition, so the user's pan is preserved.
        val lastFitToken = remember { mutableStateOf<Int?>(null) }

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
                    // Marker pin bitmaps (drawn in code — no drawable assets). The tip of
                    // each teardrop sits on its coordinate via ICON_ANCHOR_BOTTOM.
                    val density = context.resources.displayMetrics.density
                    style.addImage(PUCK_ICON, buildRiderBadge(density, PUCK_COLOR))
                    style.addImage(DEST_ICON, buildPin(density, DEST_COLOR))

                    style.addSource(GeoJsonSource(ROUTE_SRC))
                    style.addLayer(
                        LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                            PropertyFactory.lineColor(ROUTE_COLOR),
                            PropertyFactory.lineWidth(6f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    // Rider position — blue badge with a white motorbike glyph, rotated
                    // to the heading. Rotation is MAP-aligned + driven by the per-fix
                    // "bearing" property: the glyph faces east (right) by default, so the
                    // −90° offset points it north-up at bearing 0. Because the follow
                    // camera rotates by the same bearing (see CockpitMap), the bike reads
                    // "up" during heading-up nav and points along travel on the north-up
                    // overview.
                    style.addSource(GeoJsonSource(PUCK_SRC))
                    style.addLayer(
                        SymbolLayer(PUCK_LAYER, PUCK_SRC).withProperties(
                            PropertyFactory.iconImage(PUCK_ICON),
                            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                            PropertyFactory.iconRotate(
                                Expression.subtract(Expression.get(PROP_BEARING), Expression.literal(90f)),
                            ),
                        ),
                    )
                    // Destination — red location pin (dropped pin / picked place).
                    style.addSource(GeoJsonSource(DEST_SRC))
                    style.addLayer(
                        SymbolLayer(DEST_LAYER, DEST_SRC).withProperties(
                            PropertyFactory.iconImage(DEST_ICON),
                            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                        ),
                    )
                    map = m
                }
                // Long-press anywhere → drop a destination there.
                m.addOnMapLongClickListener { point ->
                    longPress.value(GeoLocation(latitude = point.latitude, longitude = point.longitude))
                    true
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

            if (camera.fitBounds.size >= 2 && camera.fitToken != lastFitToken.value) {
                // Fit the whole route once for this token (new destination / Overview),
                // north-up, inset by the measured search-bar / sheet padding.
                val builder = LatLngBounds.Builder()
                camera.fitBounds.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
                runCatching { builder.build() }.getOrNull()?.let { bounds ->
                    lastFitToken.value = camera.fitToken
                    lastApplied.value = null // so the follow camera re-eases smoothly afterwards
                    val p = camera.fitPadding
                    m.easeCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds, p.left, p.top, p.right, p.bottom),
                        CAMERA_ANIM_MS,
                    )
                }
            } else if (camera.fitBounds.size < 2) {
                // Follow mode — battery-conscious move gate.
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

            // Location puck — carry the heading so the motorbike glyph rotates to it.
            val puckFc = if (content.showLocationPuck && content.location != null) {
                val f = Feature.fromGeometry(content.location.toPoint())
                f.addNumberProperty(PROP_BEARING, content.location.bearingDeg ?: 0f)
                FeatureCollection.fromFeature(f)
            } else {
                FeatureCollection.fromFeatures(emptyArray<Feature>())
            }
            m.style?.getSourceAs<GeoJsonSource>(PUCK_SRC)?.setGeoJson(puckFc)

            // Destination marker.
            val destFc = content.destination?.let {
                FeatureCollection.fromFeature(Feature.fromGeometry(it.toPoint()))
            } ?: FeatureCollection.fromFeatures(emptyArray<Feature>())
            m.style?.getSourceAs<GeoJsonSource>(DEST_SRC)?.setGeoJson(destFc)
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }

    private fun GeoLocation.toPoint(): Point = Point.fromLngLat(longitude, latitude)

    /**
     * Draw a classic teardrop location pin in [colorHex] with a white halo + inner dot,
     * so both markers read as "location icons" against any basemap. Sized in dp (scaled
     * by [density]); the tip is at the bottom center — pair with ICON_ANCHOR_BOTTOM so it
     * lands on the coordinate.
     */
    private fun buildPin(density: Float, colorHex: String): Bitmap {
        val w = (28f * density)
        val h = (40f * density)
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val cx = w / 2f
        val headR = w * 0.34f
        val cy = headR + 2f * density              // top margin leaves room for the halo
        val tipY = h - 1f * density

        // A teardrop = head circle unioned with a triangle down to the tip.
        fun pinPath(radius: Float): Path = Path().apply {
            addCircle(cx, cy, radius, Path.Direction.CW)
            val flank = radius * 0.80f
            val shoulder = cy + radius * 0.55f
            val tri = Path().apply {
                moveTo(cx - flank, shoulder)
                lineTo(cx + flank, shoulder)
                lineTo(cx, tipY)
                close()
            }
            op(tri, Path.Op.UNION)
        }

        paint.color = Color.WHITE                  // halo / outline
        canvas.drawPath(pinPath(headR + 2f * density), paint)
        paint.color = Color.parseColor(colorHex)   // colored body
        canvas.drawPath(pinPath(headR), paint)
        paint.color = Color.WHITE                  // inner dot
        canvas.drawCircle(cx, cy, headR * 0.42f, paint)
        return bmp
    }

    /**
     * Draw the rider marker: a filled [colorHex] disc with a white ring and a white
     * motorbike glyph (Material "two_wheeler", the same 24×24 path used by
     * [com.example.displayapp.presentation.ui.icons.EvIcons.Motorcycle]) centered inside.
     * A round badge stays upright and legible for a moving position; pair with
     * ICON_ANCHOR_CENTER so it sits on the coordinate.
     */
    private fun buildRiderBadge(density: Float, colorHex: String): Bitmap {
        val size = (34f * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val c = size / 2f
        val r = c - 1f * density
        paint.color = Color.WHITE                  // ring
        canvas.drawCircle(c, c, r, paint)
        paint.color = Color.parseColor(colorHex)   // disc
        canvas.drawCircle(c, c, r - 2f * density, paint)

        // Fit the 24×24 glyph into ~64% of the disc and center it.
        val glyph = PathParser.createPathFromPathData(MOTORBIKE_PATH)
        val target = (r - 2f * density) * 2f * 0.64f
        val scale = target / 24f
        Matrix().apply {
            setScale(scale, scale)
            postTranslate(c - target / 2f, c - target / 2f)
        }.let { glyph.transform(it) }
        paint.color = Color.WHITE
        canvas.drawPath(glyph, paint)
        return bmp
    }

    private companion object {
        const val ROUTE_SRC = "route-src"
        const val ROUTE_LAYER = "route-layer"
        const val PUCK_SRC = "puck-src"
        const val PUCK_LAYER = "puck-layer"
        const val PUCK_ICON = "puck-icon"
        const val PROP_BEARING = "bearing"  // per-fix heading → motorbike icon rotation
        const val DEST_SRC = "dest-src"
        const val DEST_LAYER = "dest-layer"
        const val DEST_ICON = "dest-icon"
        const val ROUTE_COLOR = "#2563EB"
        const val PUCK_COLOR = "#3B82F6"  // blue rider badge
        const val DEST_COLOR = "#EF4444"  // red destination pin
        // Material Icons "two_wheeler" (Apache 2.0), 24×24 — matches EvIcons.Motorcycle.
        const val MOTORBIKE_PATH =
            "M20,11c-0.18,0-0.36,0.03-0.53,0.05L17.41,9H20V6l-3.72,1.86L13.41,5H9v2h3.59l2,2H11l-4,2L5,9H0v2h4" +
                "c-2.21,0-4,1.79-4,4c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4l2,2h3l3.49-6.1l1.01,1.01" +
                "C16.59,12.64,16,13.75,16,15c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4C24,12.79,22.21,11,20,11z" +
                "M4,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2c1.1,0,2,0.9,2,2C6,16.1,5.1,17,4,17z" +
                "M20,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2s2,0.9,2,2C22,16.1,21.1,17,20,17z"
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
