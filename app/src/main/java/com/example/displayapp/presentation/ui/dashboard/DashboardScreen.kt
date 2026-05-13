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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.data.energy.EnergyFormatter
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
        SpeedometerSection(state = state)
        // Drive-mode selector sits directly below the speedometer so quick-
        // glance recognition has the gauge + active mode in one frame.
        ModeCard(mode = state.vehicleMode)
        BatteryRowCard(batteryPercent = state.batteryPercent)
        TelemetryGrid(state = state)
        MiniMapCard(
            viewModel = mapsViewModel,
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
                MiniMapCard(
                    viewModel = mapsViewModel,
                    onOpenFullscreen = onOpenNavigation
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                BatteryRowCard(batteryPercent = state.batteryPercent)
                TelemetryGrid(state = state)
            }
        }
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
/*  EV telemetry grid — eight tiles, purely electric-vehicle data.            */
/*                                                                            */
/*  Row 1: Power           | Current                                          */
/*  Row 2: Voltage         | Engine Temp                                      */
/*  Row 3: Battery Temp    | Controller Temp                                  */
/*  Row 4: Wh/km           | Range                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun TelemetryGrid(state: DashboardUiState) {
    val app = LocalAppSettings.current
    // All values come straight off canonical telemetry — no UI math. Power
    // is derived in TelemetryMapper; temperatures are real wire channels.
    val voltage = state.voltage
    val current = state.current
    val power   = state.power

    // Convert telemetry's °C readings to whatever unit the user picked.
    val engineTemp     = app.temperatureUnit.convertFromCelsius(state.temperature.toFloat())
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
                icon = EvIcons.Plug,
                label = "Power",
                value = power,
                unit = "Watt",
                fractionDigits = 1,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PremiumMetricTile(
                icon = EvIcons.Bolt,
                label = "Current",
                value = current,
                unit = "A",
                fractionDigits = 1,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
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
            PremiumMetricTile(
                icon = EvIcons.Thermo,
                label = "Engine Temp",
                value = engineTemp,
                unit = tempUnit,
                level = temperatureAlertLevel(state.temperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            PremiumMetricTile(
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
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            PremiumMetricTile(
                icon = EvIcons.Plug,
                label = "Wh/km",
                valueText = EnergyFormatter.formatEfficiency(state.efficiency.whPerKm),
                unit = "Wh/km",
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PremiumMetricTile(
                icon = EvIcons.Battery,
                label = "Range",
                valueText = EnergyFormatter.formatRangeKm(state.efficiency.rangeKm),
                unit = "km",
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
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
