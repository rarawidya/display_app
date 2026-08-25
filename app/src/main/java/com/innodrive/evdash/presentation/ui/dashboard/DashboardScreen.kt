package com.innodrive.evdash.presentation.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.presentation.state.AlertLevel
import com.innodrive.evdash.presentation.state.DashboardUiState
import com.innodrive.evdash.presentation.state.batteryAlertLevel
import com.innodrive.evdash.presentation.state.temperatureAlertLevel
import com.innodrive.evdash.presentation.ui.common.LocalAppSettings
import com.innodrive.evdash.ui.theme.EvRed
import com.innodrive.evdash.presentation.ui.components.DiagnosticsOverlay
import com.innodrive.evdash.presentation.ui.components.PremiumMetricTile
import com.innodrive.evdash.presentation.ui.components.SpeedometerGauge
import com.innodrive.evdash.presentation.ui.components.mode.ModeCard
import com.innodrive.evdash.presentation.ui.components.mode.visuals
import com.innodrive.evdash.presentation.ui.icons.EvIcons
import com.innodrive.evdash.presentation.ui.maps.MiniMapCard
import com.innodrive.evdash.presentation.viewmodel.DashboardViewModel
import com.innodrive.evdash.presentation.viewmodel.MapsViewModel
import com.innodrive.evdash.ui.theme.Dim
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    onBluetoothLongPress: () -> Unit = onConnectionTap,
    bluetoothAnchor: @Composable (Modifier) -> Unit = {},
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {},
    onLocationPermissionGranted: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()

    // Keep the screen awake while the vehicle is connected so the cockpit never
    // sleeps mid-ride. Scoped to the Drive route and gated on CONNECTED so a
    // disconnected/backgrounded page doesn't hold the display on — released
    // automatically when Drive leaves composition or the link drops.
    val view = LocalView.current
    val keepAwake = uiState.connectionState == ConnectionState.CONNECTED
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    LocationPermissionEffect(onGranted = {
        // Restart the VM's own puck stream AND the shared source, so the app-scoped
        // nav coordinator/provider recover too (they collect the flow once).
        mapsViewModel.onLocationPermissionGranted()
        onLocationPermissionGranted()
    })

    DashboardContent(
        state = uiState,
        mapsViewModel = mapsViewModel,
        wifiConnected = wifiConnected,
        showDiagnostics = showDiagnostics,
        onConnectionTap = onConnectionTap,
        onSettingsTap = onSettingsTap,
        onOpenNavigation = onOpenNavigation,
        onBluetoothLongPress = onBluetoothLongPress,
        bluetoothAnchor = bluetoothAnchor,
        onToggleDiagnostics = viewModel::toggleDiagnostics,
        hotspotActive = hotspotActive,
        onHotspotTap = onHotspotTap
    )
}

/**
 * Asks for location permission when the Drive page opens, so the mini-map's blue
 * dot works without a detour through system Settings. Non-blocking: on denial the
 * page renders normally and the map simply has no location puck. Requesting FINE +
 * COARSE together lets Android 12+ users pick "approximate" if they prefer.
 */
@Composable
private fun LocationPermissionEffect(onGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.any { it }) onGranted() }

    LaunchedEffect(Unit) {
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}

/**
 * Critical-battery warning banner for the Drive page. Renders nothing unless the
 * vehicle is connected, the pack SoC is known and not charging, and the battery
 * is in the CRITICAL band (≤ 20 %, via [batteryAlertLevel]). Shows the rolling
 * range estimate as context when available.
 *
 * Dismiss is sticky per 5 % bucket: hiding it at 20 % keeps it hidden until the
 * pack drops to the next bucket (15 %, 10 %, …), so a worsening situation nags
 * again; recovery / charging / disconnect resets the dismissal entirely.
 */
@Composable
private fun LowBatteryBanner(state: DashboardUiState) {
    val active = state.connectionState == ConnectionState.CONNECTED &&
        state.batteryKnown && !state.charging &&
        batteryAlertLevel(state.batteryPercent) == AlertLevel.CRITICAL

    val bucket = state.batteryPercent / 5
    var dismissedBucket by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    LaunchedEffect(active) { if (!active) dismissedBucket = Int.MAX_VALUE }
    if (!active || bucket >= dismissedBucket) return

    val app = LocalAppSettings.current
    val rangeText = state.efficiency.rangeKm
        ?.takeIf { it > 0f }
        ?.let { " · ~${app.speedUnit.formatDistance((it * 1000).toLong())} left" }
        .orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EvRed.copy(alpha = 0.14f))
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Icon(
            imageVector = EvIcons.Warning,
            contentDescription = null,
            tint = EvRed,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "Battery critical",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${state.batteryPercent}% remaining$rangeText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "✕",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { dismissedBucket = bucket }
                .padding(horizontal = Dim.sm, vertical = Dim.xxs)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardContent(
    state: DashboardUiState,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean = false,
    showDiagnostics: Boolean = false,
    onConnectionTap: () -> Unit = {},
    onSettingsTap: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    onBluetoothLongPress: () -> Unit = onConnectionTap,
    bluetoothAnchor: @Composable (Modifier) -> Unit = {},
    onToggleDiagnostics: () -> Unit = {},
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .ambientGlow(state)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onToggleDiagnostics
                )
        ) {
            // Note: don't add statusBarsPadding here — the parent Scaffold's
            // innerPadding already accounts for status-bar insets, and adding it
            // again would push the header down by the status-bar height twice.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 720.dp) {
                    DashboardLandscape(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation, onBluetoothLongPress, bluetoothAnchor, hotspotActive, onHotspotTap)
                } else {
                    DashboardPortrait(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation, onBluetoothLongPress, bluetoothAnchor, hotspotActive, onHotspotTap)
                }
            }

            if (showDiagnostics) {
                DiagnosticsOverlay(
                    diagnostics = state.diagnostics,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dim.md)
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Phone (portrait) layout                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun DashboardPortrait(
    state: DashboardUiState,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit,
    onOpenNavigation: () -> Unit,
    onBluetoothLongPress: () -> Unit,
    bluetoothAnchor: @Composable (Modifier) -> Unit,
    hotspotActive: Boolean,
    onHotspotTap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenGutter)
            // Proportional top breathing room — see Dim.screenTop. Same token is
            // used on Chart / Logs / Settings so all top-level pages align.
            .padding(top = Dim.screenTop, bottom = Dim.lg),
        verticalArrangement = Arrangement.spacedBy(Dim.md)
    ) {
        BrandHeader(
            connectionState = state.connectionState,
            wifiConnected = wifiConnected,
            onConnectTap = onConnectionTap,
            onSettingsTap = onSettingsTap,
            onBluetoothLongPress = onBluetoothLongPress,
            bluetoothAnchor = bluetoothAnchor,
            hotspotActive = hotspotActive,
            onHotspotTap = onHotspotTap
        )
        // Critical-battery alert sits above everything so a low pack is the first
        // thing the rider sees; renders nothing when the pack is healthy.
        LowBatteryBanner(state = state)
        // Warning-lamp strip mirrors the physical cluster's top telltale row.
        TelltaleRow(state = state)
        SpeedometerSection(state = state)
        // Drive-mode selector sits directly below the speedometer so quick-
        // glance recognition has the gauge + active mode in one frame.
        ModeCard(mode = state.vehicleMode)
        BatteryRowCard(batteryPercent = state.batteryPercent, known = state.batteryKnown, charging = state.charging)
        TelemetryGrid(state = state)
        TitledMiniMap(
            mapsViewModel = mapsViewModel,
            onOpenFullscreen = onOpenNavigation
        )
        Spacer(Modifier.height(Dim.sm))
    }
}

/* -------------------------------------------------------------------------- */
/*  Tablet / landscape layout                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun DashboardLandscape(
    state: DashboardUiState,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit,
    onOpenNavigation: () -> Unit,
    onBluetoothLongPress: () -> Unit,
    bluetoothAnchor: @Composable (Modifier) -> Unit,
    hotspotActive: Boolean,
    onHotspotTap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenGutterLg, vertical = Dim.md),
        verticalArrangement = Arrangement.spacedBy(Dim.md)
    ) {
        BrandHeader(
            connectionState = state.connectionState,
            wifiConnected = wifiConnected,
            onConnectTap = onConnectionTap,
            onSettingsTap = onSettingsTap,
            onBluetoothLongPress = onBluetoothLongPress,
            bluetoothAnchor = bluetoothAnchor,
            hotspotActive = hotspotActive,
            onHotspotTap = onHotspotTap
        )
        LowBatteryBanner(state = state)
        TelltaleRow(state = state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dim.lg)
        ) {
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                SpeedometerSection(state = state)
                ModeCard(mode = state.vehicleMode)
                TitledMiniMap(
                    mapsViewModel = mapsViewModel,
                    onOpenFullscreen = onOpenNavigation
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                BatteryRowCard(batteryPercent = state.batteryPercent, known = state.batteryKnown, charging = state.charging)
                TelemetryGrid(state = state)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Map section                                                               */
/* -------------------------------------------------------------------------- */

/** Mini-map with a section title above it, matching the Home page's card headers. */
@Composable
private fun TitledMiniMap(
    mapsViewModel: MapsViewModel,
    onOpenFullscreen: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
        Text(
            text = "Navigation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        MiniMapCard(
            viewModel = mapsViewModel,
            onOpenFullscreen = onOpenFullscreen
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Speedometer section                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun SpeedometerSection(state: DashboardUiState) {
    val app = LocalAppSettings.current
    // Only the arc is range-bound: speed is clamped to 0..180 km/h so the progress
    // fraction can't overshoot the dial. Speed and rpm are real wire fields
    // (TelemetryMapper — single source of truth); the readouts show them verbatim.
    val clampedSpeedKmh = state.speed.coerceIn(0, GAUGE_MAX_SPEED_KMH)
    val progress = clampedSpeedKmh.toFloat() / GAUGE_MAX_SPEED_KMH
    // rpm drives only the text readout (not the arc), so show the true value rather
    // than capping it — a clamp here would misreport a genuine over-range rpm.
    val rpm = state.rpm.coerceAtLeast(0)

    // Hero = speed (honors the km/h ↔ mph preference); secondary = rpm.
    val speedDisplay = app.speedUnit.convertFromKmh(clampedSpeedKmh.toFloat()).roundToInt()

    // Dial scale numbers on the major tick lines: round km/h majors (0,20,…,180)
    // converted into the active unit so the arc tip reads against a real value.
    val scaleLabels = (0..GAUGE_MAX_SPEED_KMH step GAUGE_MAJOR_STEP_KMH).map { kmh ->
        app.speedUnit.convertFromKmh(kmh.toFloat()).roundToInt().toString()
    }

    SpeedometerGauge(
        heroValue = speedDisplay,
        heroUnit = app.speedUnit.suffix.uppercase(),
        progressFraction = progress,
        secondaryText = "%,d RPM".format(rpm),
        scaleLabels = scaleLabels,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Drive-gauge arc full-scale + major-tick spacing (km/h) — mirror the cluster dial. */
private const val GAUGE_MAX_SPEED_KMH = 180
private const val GAUGE_MAJOR_STEP_KMH = 20

/* -------------------------------------------------------------------------- */
/*  EV telemetry grid — real controller channels only (capnp.md).             */
/*                                                                            */
/*  Row 1: Voltage         | Current                                          */
/*  Row 2: Power           | Motor Temp                                       */
/*  Row 3: Battery Temp    | Controller Temp                                  */
/*                                                                            */
/*  Current & Power are now wire-backed: `currentMotor` (@1) is calibrated    */
/*  signed deci-amps, so power = V×I is live. They still route through        */
/*  MetricOrDash on `currentAvailable` so a future de-calibration falls back  */
/*  to "—". Battery Temp is absent from the v1 wire (always "—").             */
/* -------------------------------------------------------------------------- */

@Composable
private fun TelemetryGrid(state: DashboardUiState) {
    val app = LocalAppSettings.current
    val voltage = state.voltage

    // Convert telemetry's °C readings to whatever unit the user picked.
    val motorTemp      = app.temperatureUnit.convertFromCelsius(state.temperature.toFloat())
    val batteryTemp    = app.temperatureUnit.convertFromCelsius(state.batteryTemperature.toFloat())
    val controllerTemp = app.temperatureUnit.convertFromCelsius(state.controllerTemperature.toFloat())
    val tempUnit       = app.temperatureUnit.suffix

    // Each tile uses `Modifier.weight(1f).fillMaxHeight()`:
    //   - weight equalizes widths across siblings in the row,
    //   - fillMaxHeight + the row's IntrinsicSize.Max constraint equalizes heights.
    // Result: every card in a row renders the same width and height.

    Column(verticalArrangement = Arrangement.spacedBy(Dim.md)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            PremiumMetricTile(
                icon = EvIcons.Bolt,
                label = "Voltage",
                value = voltage,
                unit = "V",
                fractionDigits = 1,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            // Motor current — signed deci-amps off the wire (negative = regen).
            MetricOrDash(
                available = state.currentAvailable,
                icon = EvIcons.Pulse,
                label = "Current",
                value = state.current,
                unit = "A",
                fractionDigits = 1,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            // Bus power = voltage × current (derived once in TelemetryDerivations).
            MetricOrDash(
                available = state.currentAvailable,
                icon = EvIcons.Plug,
                label = "Power",
                value = state.power,
                unit = "W",
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PremiumMetricTile(
                icon = EvIcons.Thermo,
                label = "Motor Temp",
                value = motorTemp,
                unit = tempUnit,
                level = temperatureAlertLevel(state.temperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            MetricOrDash(
                available = state.batteryTempAvailable,
                icon = EvIcons.Battery,
                label = "Battery Temp",
                value = batteryTemp,
                unit = tempUnit,
                level = temperatureAlertLevel(state.batteryTemperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PremiumMetricTile(
                icon = EvIcons.Thermo,
                label = "Controller Temp",
                value = controllerTemp,
                unit = tempUnit,
                level = temperatureAlertLevel(state.controllerTemperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/**
 * A metric tile that shows its animated numeric readout when [available], and a
 * static "—" otherwise — for wire fields the current protocol can't provide
 * (uncalibrated current/power, absent battery-temp). Avoids presenting a
 * meaningless 0 as if it were a real reading.
 */
@Composable
private fun MetricOrDash(
    available: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: Float,
    unit: String,
    modifier: Modifier = Modifier,
    level: com.innodrive.evdash.presentation.state.AlertLevel =
        com.innodrive.evdash.presentation.state.AlertLevel.NORMAL,
    fractionDigits: Int = 0
) {
    if (available) {
        PremiumMetricTile(
            icon = icon,
            label = label,
            value = value,
            unit = unit,
            modifier = modifier,
            level = level,
            fractionDigits = fractionDigits
        )
    } else {
        PremiumMetricTile(
            icon = icon,
            label = label,
            valueText = "—",
            unit = unit,
            modifier = modifier
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Ambient background glow — driven by the shared ModeVisuals catalog so the */
/*  Drive backdrop, ModeCard, and any future mode-aware widget stay in sync.  */
/* -------------------------------------------------------------------------- */

@Composable
private fun Modifier.ambientGlow(state: DashboardUiState): Modifier {
    val visuals = state.vehicleMode.visuals()
    // Map the catalog intensity (0..1) onto a subtle alpha range. PARK
    // (intensity 0) collapses to fully transparent so the cockpit doesn't
    // hum visually while stationary.
    val tint = visuals.accent.copy(alpha = 0.05f + 0.07f * visuals.intensity)
    val brush = Brush.verticalGradient(
        listOf(tint, Color.Transparent)
    )
    return this.then(Modifier.background(brush))
}
