package com.example.displayapp.presentation.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.PathParser
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.R
import com.example.displayapp.data.format.Formatters
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed
import com.example.displayapp.ui.theme.EvViolet
import java.time.LocalTime

/**
 * Home / landing screen — the first cockpit tab.
 *
 * A calm "at rest" overview (mirrors the product mockup): greeting + vehicle
 * name, a hero call-to-action, a compact stats strip, a vehicle-health grid and
 * today's ride summary. It reuses [DashboardViewModel] so every number here is
 * bit-identical to the Drive tab — no second derivation site.
 *
 * Design rules:
 *  - Cards with no real data are omitted, not shown as zeros (see [QuickStatsCard],
 *    [TodaySummaryCard]). Uncalibrated current ⇒ no efficiency/range tile, etc.
 *  - Layout is proportional (Dim tokens) and centered/​capped on wide screens.
 */
/** The most recent completed trip, surfaced in the "Last Ride" summary card. */
data class LastRide(
    val distanceMeters: Long,
    val durationSec: Long,
    val startMs: Long
)

@Composable
fun HomeScreen(
    viewModel: DashboardViewModel,
    deviceName: String,
    onStartMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBluetooth: () -> Unit,
    lastRide: LastRide? = null,
    /** Last navigated route's polyline — drawn in the Last Ride map thumbnail. */
    lastRoutePoints: List<GeoLocation>? = null,
    onEditDevice: () -> Unit = onOpenSettings,
    /** Phone hotspot (soft AP) state for the header indicator; tap → tethering settings. */
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        deviceName = deviceName,
        lastRide = lastRide,
        lastRoutePoints = lastRoutePoints,
        hotspotActive = hotspotActive,
        onHotspotTap = onHotspotTap,
        onStartMonitoring = onStartMonitoring,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        onOpenBluetooth = onOpenBluetooth,
        onEditDevice = onEditDevice,
        onResetTripA = viewModel::resetTripOdometer,
        onResetTripB = viewModel::resetTripB
    )
}

@Composable
private fun HomeContent(
    state: DashboardUiState,
    onResetTripA: () -> Unit = {},
    onResetTripB: () -> Unit = {},
    deviceName: String,
    lastRide: LastRide?,
    lastRoutePoints: List<GeoLocation>? = null,
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {},
    onStartMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onEditDevice: () -> Unit
) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Cap content width on tablets so lines don't stretch uncomfortably.
            val contentWidth = if (maxWidth >= 720.dp) 640.dp else maxWidth
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = contentWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dim.screenGutter)
                    .padding(top = Dim.screenTop, bottom = Dim.xl),
                verticalArrangement = Arrangement.spacedBy(Dim.lg)
            ) {
                GreetingHeader(
                    deviceName = deviceName,
                    connectionState = state.connectionState,
                    onOpenBluetooth = onOpenBluetooth,
                    onEditDevice = onEditDevice,
                    onOpenSettings = onOpenSettings,
                    hotspotActive = hotspotActive,
                    onHotspotTap = onHotspotTap
                )
                HeroCard(
                    connected = connected,
                    onStartMonitoring = onStartMonitoring,
                    onConnect = onOpenBluetooth
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dim.md)
                ) {
                    // Last Ride is wider than Battery so its stats + map don't crowd
                    // (mirrors the home_battery.png proportions).
                    BatteryCard(
                        state = state,
                        connected = connected,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    LastRideCard(
                        lastRide = lastRide,
                        routePoints = lastRoutePoints,
                        onOpenHistory = onOpenHistory,
                        modifier = Modifier.weight(1.35f).fillMaxHeight()
                    )
                }
                VehicleInfoCard(
                    state = state,
                    onResetTripA = onResetTripA,
                    onResetTripB = onResetTripB
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Greeting header                                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun GreetingHeader(
    deviceName: String,
    connectionState: ConnectionState,
    onOpenBluetooth: () -> Unit,
    onEditDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Text(
                text = "${greeting()} 👋",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dim.sm)
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                RoundIcon(
                    icon = EvIcons.Edit,
                    contentDescription = "Rename vehicle",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                    onClick = onEditDevice
                )
            }
            ConnectionLine(connectionState, onOpenBluetooth)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
            // Hotspot state — green while the phone's soft AP is on (the vehicle
            // display joins it for map downloads). Tap → tethering settings.
            SquareIconButton(
                icon = EvIcons.Hotspot,
                contentDescription = if (hotspotActive) "Hotspot on" else "Hotspot off",
                tint = if (hotspotActive) EvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onHotspotTap
            )
            SquareIconButton(
                icon = EvIcons.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun ConnectionLine(connectionState: ConnectionState, onOpenBluetooth: () -> Unit) {
    val connected = connectionState == ConnectionState.CONNECTED
    val (label, color) = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected" to EvGreen
        ConnectionState.CONNECTING -> "Connecting…" to EvAmber
        ConnectionState.RECONNECTING -> "Reconnecting…" to EvAmber
        else -> "Tap to connect" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onOpenBluetooth)
            .padding(start = Dim.xxs, top = Dim.xxs, end = Dim.xs, bottom = Dim.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (connected) EvIcons.Bluetooth else EvIcons.BluetoothOff,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        // Chevron cue that the row is actionable when there's nothing live yet.
        if (!connected) {
            androidx.compose.material3.Icon(
                imageVector = EvIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Hero call-to-action                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun HeroCard(
    connected: Boolean,
    onStartMonitoring: () -> Unit,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(Dim.cardCorner))
    ) {
        // Full-bleed hero artwork (scooter on a dark studio background) as the card bg.
        Image(
            painter = painterResource(id = R.drawable.heroimage),
            contentDescription = "Your electric scooter",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Left scrim to deepen the dark side and keep the text/button crisp.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.55f to Color.Black.copy(alpha = 0.15f),
                        1f to Color.Transparent
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.58f)
                .padding(start = Dim.xl, end = Dim.sm),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Text(
                text = if (connected) "Ready to ride" else "Let's connect",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = if (connected) "Your scooter is ready whenever you are."
                       else "Pair your scooter over Bluetooth to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f)
            )
            Spacer(Modifier.height(Dim.xs))
            HeroButton(
                label = if (connected) "Start Monitoring" else "Connect Bluetooth",
                icon = if (connected) EvIcons.ChevronRight else EvIcons.Bluetooth,
                onClick = if (connected) onStartMonitoring else onConnect
            )
        }
    }
}

@Composable
private fun HeroButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dim.md, vertical = Dim.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                softWrap = false
            )
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Battery + Today's ride — paired summary cards (see home_battery.png).      */
/* -------------------------------------------------------------------------- */

@Composable
private fun BatteryCard(
    state: DashboardUiState,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val app = LocalAppSettings.current
    val known = connected && state.batteryKnown
    val pct = state.batteryPercent.coerceIn(0, 100)
    val fillColor = when {
        !known -> MaterialTheme.colorScheme.outline
        pct > 50 -> EvGreen
        pct > 20 -> EvAmber
        else -> EvRed
    }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    // Estimated range = remaining pack energy ÷ rolling Wh/km (EfficiencyTracker.
    // computeRangeKm). Null until there's valid consumption data, so we show "—"
    // rather than a fabricated number. Displayed in the user's distance unit.
    val rangeKm = state.efficiency.rangeKm
    val rangeValue = if (known && rangeKm != null) {
        val v = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH)
            rangeKm * 0.621371f else rangeKm
        "%.0f".format(v)
    } else "—"

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dim.lg),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top group — title, big percentage + glyph, charge bar.
            Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
                Text(
                    text = "Battery",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = if (known) "$pct" else "—",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (known) {
                            Text(
                                text = "%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    // Battery glyph, tinted to the charge state.
                    androidx.compose.material3.Icon(
                        imageVector = EvIcons.Battery,
                        contentDescription = null,
                        tint = if (known) fillColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(34.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(trackColor)
                ) {
                    if (known) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct / 100f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(fillColor)
                        )
                    }
                }
            }
            // Bottom — estimated range, anchored to the card base.
            Column(verticalArrangement = Arrangement.spacedBy(Dim.xxs)) {
                Text(
                    text = "Estimated Range",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = rangeValue,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = " ${app.speedUnit.distanceSuffix}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LastRideCard(
    lastRide: LastRide?,
    routePoints: List<GeoLocation>?,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = LocalAppSettings.current
    val distanceKm = (lastRide?.distanceMeters ?: 0L) / 1000f
    val distDisplay = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH)
        distanceKm * 0.621371f else distanceKm
    val distanceValue = if (lastRide != null) "%.1f".format(distDisplay) else "—"
    // Round to the nearest minute (+30 s before integer-dividing) so a 6 m 51 s
    // ride reads "7 min", not a floored "6".
    val durationValue = if (lastRide != null) "${(lastRide.durationSec + 30) / 60}" else "—"
    val dateText = lastRide?.let { Formatters.dateTime(it.startMs, app.timeFormat) } ?: "No rides yet"

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Last Ride",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Icon(
                    imageVector = EvIcons.ChevronRight,
                    contentDescription = "View history",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenHistory)
                )
            }
            // Day + time of the ride, sitting just below the title.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dim.xxs)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = EvIcons.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = Dim.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dim.md)
                ) {
                    RideMetric(distanceValue, app.speedUnit.distanceSuffix, "Distance")
                    RideMetric(durationValue, "min", "Duration")
                }
                RouteMini(
                    points = routePoints,
                    modifier = Modifier
                        .padding(start = Dim.sm)
                        .fillMaxHeight()
                        .width(80.dp)
                )
            }
        }
    }
}

@Composable
private fun RideMetric(value: String, unit: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.xxs)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = " $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * Route/map thumbnail. With [points] (the last navigated route, persisted by
 * `LastRouteStore`) it draws the **real route shape** — north-up, aspect-preserving,
 * cos(lat)-corrected — over the stylised street grid. Without one it falls back to
 * the decorative placeholder line. No Maps API/tiles involved either way.
 */
@Composable
private fun RouteMini(points: List<GeoLocation>? = null, modifier: Modifier = Modifier) {
    val route = MaterialTheme.colorScheme.primary
    val road = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
    val roadMinor = MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val marker = MaterialTheme.colorScheme.surface
    val riderDot = EvBlue   // rider start — matches the map's blue rider marker
    val destColor = EvRed   // destination — matches the map's red location pin
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dim.md))
            .background(bg)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun px(v: Float) = v.dp.toPx()

            // Major streets — a slightly skewed grid so it reads as a real map.
            drawLine(road, Offset(0f, h * 0.30f), Offset(w, h * 0.24f), strokeWidth = px(3f))
            drawLine(road, Offset(0f, h * 0.68f), Offset(w, h * 0.74f), strokeWidth = px(3f))
            drawLine(road, Offset(w * 0.32f, 0f), Offset(w * 0.26f, h), strokeWidth = px(3f))
            drawLine(road, Offset(w * 0.74f, 0f), Offset(w * 0.80f, h), strokeWidth = px(3f))
            // Minor + diagonal streets
            drawLine(roadMinor, Offset(0f, h * 0.92f), Offset(w, h * 0.08f), strokeWidth = px(1.5f))
            drawLine(roadMinor, Offset(w * 0.52f, 0f), Offset(w * 0.47f, h), strokeWidth = px(1.5f))
            drawLine(roadMinor, Offset(0f, h * 0.50f), Offset(w, h * 0.48f), strokeWidth = px(1.5f))

            // The route line: real geometry when we have it, placeholder otherwise.
            val line = points?.takeIf { it.size >= 2 }?.let { pts -> fitToCanvas(pts, w, h) }
                ?: Path().apply {
                    moveTo(w * 0.16f, h * 0.86f)
                    cubicTo(w * 0.32f, h * 0.66f, w * 0.22f, h * 0.52f, w * 0.44f, h * 0.46f)
                    cubicTo(w * 0.62f, h * 0.40f, w * 0.60f, h * 0.28f, w * 0.84f, h * 0.16f)
                }.let { it to (Offset(w * 0.16f, h * 0.86f) to Offset(w * 0.84f, h * 0.16f)) }

            val (path, endpoints) = line
            drawPath(
                path, route,
                style = Stroke(width = px(3.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Rider start (blue motorbike badge) + destination (red pin, tip on the
            // route end) — matching the map's marker language.
            val (start, end) = endpoints
            drawRiderBadge(start, disc = riderDot, halo = marker)
            drawDestinationPin(end, fill = destColor, halo = marker)
        }
    }
}

/** Material Icons "two_wheeler" (Apache 2.0), 24×24 — matches EvIcons.Motorcycle. */
private const val MOTORBIKE_PATH =
    "M20,11c-0.18,0-0.36,0.03-0.53,0.05L17.41,9H20V6l-3.72,1.86L13.41,5H9v2h3.59l2,2H11l-4,2L5,9H0v2h4" +
        "c-2.21,0-4,1.79-4,4c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4l2,2h3l3.49-6.1l1.01,1.01" +
        "C16.59,12.64,16,13.75,16,15c0,2.21,1.79,4,4,4c2.21,0,4-1.79,4-4C24,12.79,22.21,11,20,11z" +
        "M4,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2c1.1,0,2,0.9,2,2C6,16.1,5.1,17,4,17z" +
        "M20,17c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2s2,0.9,2,2C22,16.1,21.1,17,20,17z"

/**
 * Rider marker: a [disc]-filled circle badge with a [halo] ring and a white motorbike
 * glyph — the thumbnail echo of the map's blue motorbike rider marker.
 */
private fun DrawScope.drawRiderBadge(center: Offset, disc: Color, halo: Color) {
    val r = 7f.dp.toPx()
    drawCircle(halo, r + 1.2f.dp.toPx(), center)  // ring
    drawCircle(disc, r, center)                    // disc
    val box = r * 2f * 0.62f                        // glyph fits ~62% of the disc
    val glyph = PathParser.createPathFromPathData(MOTORBIKE_PATH).apply {
        transform(Matrix().apply {
            setScale(box / 24f, box / 24f)
            postTranslate(center.x - box / 2f, center.y - box / 2f)
        })
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb() }
    drawIntoCanvas { it.nativeCanvas.drawPath(glyph, paint) }
}

/**
 * A small teardrop location pin whose tip sits on [tip] — the thumbnail echo of the
 * map's red destination pin. Drawn as a [halo]-ringed head + triangle with a [halo]
 * center dot, so it reads on any basemap tone at thumbnail scale.
 */
private fun DrawScope.drawDestinationPin(tip: Offset, fill: Color, halo: Color) {
    val headR = 4.2f.dp.toPx()
    val head = Offset(tip.x, tip.y - 6f.dp.toPx())
    val triangle = Path().apply {
        moveTo(tip.x - headR * 0.7f, head.y + headR * 0.5f)
        lineTo(tip.x + headR * 0.7f, head.y + headR * 0.5f)
        lineTo(tip.x, tip.y)
        close()
    }
    drawCircle(halo, headR + 1.4f.dp.toPx(), head)  // white halo behind the head
    drawPath(triangle, fill)                        // colored tip
    drawCircle(fill, headR, head)                   // colored head
    drawCircle(halo, headR * 0.42f, head)           // inner white dot
}

/**
 * Project [pts] into the w×h canvas: north-up equirectangular with a cos(lat)
 * x-correction, aspect-preserving fit, centered, with a marker-safe margin.
 * Returns the path plus the projected start/end points for the markers.
 */
private fun fitToCanvas(
    pts: List<GeoLocation>,
    w: Float,
    h: Float,
): Pair<Path, Pair<Offset, Offset>> {
    val latMid = Math.toRadians(pts.sumOf { it.latitude } / pts.size)
    val xScaleDeg = kotlin.math.cos(latMid).toFloat()

    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    val proj = pts.map { p ->
        val x = p.longitude.toFloat() * xScaleDeg
        val y = -p.latitude.toFloat() // canvas y grows downward; north stays up
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        x to y
    }

    val margin = kotlin.math.min(w, h) * 0.16f // keep the ringed markers inside
    val spanX = (maxX - minX).coerceAtLeast(1e-6f)
    val spanY = (maxY - minY).coerceAtLeast(1e-6f)
    val scale = kotlin.math.min((w - 2 * margin) / spanX, (h - 2 * margin) / spanY)
    val offX = (w - spanX * scale) / 2f
    val offY = (h - spanY * scale) / 2f

    fun place(p: Pair<Float, Float>) =
        Offset(offX + (p.first - minX) * scale, offY + (p.second - minY) * scale)

    val path = Path()
    proj.forEachIndexed { i, p ->
        val o = place(p)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    return path to (place(proj.first()) to place(proj.last()))
}

/* -------------------------------------------------------------------------- */
/*  Vehicle information — spec + live-status sheet (à la NIU / Ather app).     */
/* -------------------------------------------------------------------------- */

private enum class TripReset { A, B }

@Composable
private fun VehicleInfoCard(
    state: DashboardUiState,
    onResetTripA: () -> Unit,
    onResetTripB: () -> Unit
) {
    val app = LocalAppSettings.current
    val connected = state.connectionState == ConnectionState.CONNECTED
    val dash = "—"
    var confirmReset by remember { mutableStateOf<TripReset?>(null) }

    // Odometer + Trip A/B are live. Model + Firmware come from the vehicle's BLE
    // Device Information Service (0x180A), read once on connect; "—" until read or
    // when the board doesn't expose them.
    // Trip A is the vehicle's own wire trip; Trip B is app-tracked (odometer − baseline).
    fun km(value: Float) = app.speedUnit.formatDistance((value * 1000).toLong())
    val odometerValue = if (state.odometer > 0f) km(state.odometer) else dash
    val tripAValue = if (connected) km(state.tripOdometer) else dash
    val tripBValue = if (connected) km(state.tripBOdometer) else dash
    val firmwareValue = state.firmware?.takeIf { it.isNotBlank() } ?: dash
    val modelValue = state.model?.takeIf { it.isNotBlank() } ?: dash

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Text(
            text = "Vehicle Information",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(Dim.cardCorner),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dim.lg),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                // Distances — lifetime odometer up top, the two trips nested below.
                OdometerRow(value = odometerValue)
                TripRow("Trip A", tripAValue, EvGreen, connected) { confirmReset = TripReset.A }
                TripRow("Trip B", tripBValue, EvAmber, connected) { confirmReset = TripReset.B }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

                // Vehicle metadata — two stats across.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VehicleStat(EvIcons.Cpu, EvViolet, firmwareValue, "Firmware", Modifier.weight(1f))
                    VDivider()
                    VehicleStat(EvIcons.Motorcycle, EvAmber, modelValue, "Model", Modifier.weight(1f))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                OtaUpdateEntry(currentFirmware = firmwareValue, connected = connected)
            }
        }
    }

    confirmReset?.let { which ->
        val (title, message, action) = when (which) {
            TripReset.A -> Triple(
                "Reset Trip A?",
                "Zeroes the vehicle's own trip distance. The lifetime odometer is not affected.",
                onResetTripA
            )
            TripReset.B -> Triple(
                "Reset Trip B?",
                "Zeroes this app-tracked trip. The vehicle's Trip A and lifetime odometer are not affected.",
                onResetTripB
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmReset = null; action() }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmReset = null }) { Text("Cancel") }
            }
        )
    }
}

/** Lifetime odometer — the headline distance row (icon + label left, value right). */
@Composable
private fun OdometerRow(value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(EvBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(EvIcons.Road, null, tint = EvBlue, modifier = Modifier.size(20.dp))
            }
            Text(
                text = "Odometer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * A resettable trip row nested under the odometer: accent dot + label on the left,
 * distance + "Reset" on the right. Reset is only enabled while connected (Trip A
 * needs the board; Trip B needs the current odometer to re-baseline against).
 */
@Composable
private fun TripRow(
    label: String,
    value: String,
    accent: Color,
    canReset: Boolean,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Dim.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            androidx.compose.material3.TextButton(onClick = onReset, enabled = canReset) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun VehicleStat(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = Dim.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xs)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** Vertical separator between the stacked-sideways vehicle stats. */
@Composable
private fun VDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 52.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    )
}

/* -------------------------------------------------------------------------- */
/*  Small shared pieces                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        shape = RoundedCornerShape(Dim.md),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    )
}

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else -> "Good Night"
    }
}
