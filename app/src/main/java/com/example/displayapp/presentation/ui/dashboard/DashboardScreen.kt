package com.example.displayapp.presentation.ui.dashboard

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.state.temperatureAlertLevel
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.components.DiagnosticsOverlay
import com.example.displayapp.presentation.ui.components.PremiumMetricTile
import com.example.displayapp.presentation.ui.components.SpeedometerGauge
import com.example.displayapp.presentation.ui.components.mode.ModeCard
import com.example.displayapp.presentation.ui.components.mode.visuals
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.maps.MiniMapCard
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.ui.theme.Dim

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    onBluetoothLongPress: () -> Unit = onConnectionTap,
    bluetoothAnchor: @Composable (Modifier) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()

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
        onToggleDiagnostics = viewModel::toggleDiagnostics
    )
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
    onToggleDiagnostics: () -> Unit = {}
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
                    DashboardLandscape(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation, onBluetoothLongPress, bluetoothAnchor)
                } else {
                    DashboardPortrait(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation, onBluetoothLongPress, bluetoothAnchor)
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
    bluetoothAnchor: @Composable (Modifier) -> Unit
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
            bluetoothAnchor = bluetoothAnchor
        )
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
    bluetoothAnchor: @Composable (Modifier) -> Unit
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
            bluetoothAnchor = bluetoothAnchor
        )
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
    // RPM is derived in TelemetryMapper (single source of truth) — no UI math.
    val progress = if (state.maxSpeed > 0) state.speed.toFloat() / state.maxSpeed else 0f

    // Secondary readout honors the user's speed-unit preference (km/h ↔ mph).
    val secondary = app.speedUnit.formatSpeed(state.speed.toFloat()).uppercase()

    SpeedometerGauge(
        heroValue = state.rpm,
        heroUnit = "RPM",
        progressFraction = progress,
        secondaryText = secondary,
        modifier = Modifier.fillMaxWidth()
    )
}

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
    level: com.example.displayapp.presentation.state.AlertLevel =
        com.example.displayapp.presentation.state.AlertLevel.NORMAL,
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
