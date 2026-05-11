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
import com.example.displayapp.domain.model.VehicleMode
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.state.temperatureAlertLevel
import com.example.displayapp.presentation.ui.components.DiagnosticsOverlay
import com.example.displayapp.presentation.ui.components.PremiumMetricTile
import com.example.displayapp.presentation.ui.components.SpeedometerGauge
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.maps.MiniMapCard
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.presentation.viewmodel.MapsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvViolet

/**
 * Drive screen — redesigned to match the consumer cockpit mockup.
 *
 * Layout (top → bottom):
 *   1. BrandHeader   (logo + wordmark + Connect pill + settings grid icon)
 *   2. SpeedometerGauge (semicircle with violet→magenta progress arc)
 *   3. VehicleRowCard ('GESITS G-1')
 *   4. 2×2 metric grid (Battery, Power, Current, Voltage)
 *
 * - Connection management still lives in the Scan screen; the Connect pill
 *   routes there via [onConnectionTap].
 * - Indicator lamps / mode selector / trip-stats footer were removed from
 *   this screen — the design favors a cleaner consumer look.
 * - Long-press anywhere toggles the diagnostics overlay (kept from prior UX).
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    mapsViewModel: MapsViewModel,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit = {},
    onOpenNavigation: () -> Unit = {}
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
                    DashboardLandscape(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation)
                } else {
                    DashboardPortrait(state, mapsViewModel, wifiConnected, onConnectionTap, onSettingsTap, onOpenNavigation)
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
    onOpenNavigation: () -> Unit
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
            onSettingsTap = onSettingsTap
        )
        SpeedometerSection(state = state)
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
    onOpenNavigation: () -> Unit
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
            onSettingsTap = onSettingsTap
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
    // Domain model has no RPM channel — derive a representative value from speed
    // using a 100:1 ratio (matches typical EV mid-drive at the mockup's 60 km/h ↔ 6000 RPM).
    val derivedRpm = state.speed * 100
    val progress = if (state.maxSpeed > 0) state.speed.toFloat() / state.maxSpeed else 0f

    SpeedometerGauge(
        heroValue = derivedRpm,
        heroUnit = "RPM",
        progressFraction = progress,
        secondaryText = "${state.speed} KM/H",
        modifier = Modifier.fillMaxWidth()
    )
}

/* -------------------------------------------------------------------------- */
/*  EV telemetry grid — six tiles, purely electric-vehicle data.              */
/*                                                                            */
/*  Row 1: Power           | Current                                          */
/*  Row 2: Voltage         | Engine Temp                                      */
/*  Row 3: Battery Temp    | Controller Temp                                  */
/* -------------------------------------------------------------------------- */

@Composable
private fun TelemetryGrid(state: DashboardUiState) {
    val voltage = state.voltage.toFloatOrNull() ?: 0f
    val current = state.current.toFloatOrNull() ?: 0f
    val power   = voltage * current  // derived — no direct power channel yet

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
                value = state.temperature.toFloat(),
                unit = "°C",
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
                value = state.batteryTemperature.toFloat(),
                unit = "°C",
                level = temperatureAlertLevel(state.batteryTemperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PremiumMetricTile(
                icon = EvIcons.Thermo,
                label = "Controller Temp",
                value = state.controllerTemperature.toFloat(),
                unit = "°C",
                level = temperatureAlertLevel(state.controllerTemperature),
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Ambient background glow — subtle accent based on vehicle mode             */
/* -------------------------------------------------------------------------- */

@Composable
private fun Modifier.ambientGlow(state: DashboardUiState): Modifier {
    val tint = when (state.vehicleMode) {
        VehicleMode.PARK   -> Color.Transparent
        VehicleMode.ECO    -> EvLime.copy(alpha = 0.06f)
        VehicleMode.NORMAL -> EvBlue.copy(alpha = 0.08f)
        VehicleMode.SPORT  -> EvViolet.copy(alpha = 0.10f)
    }
    val brush = Brush.verticalGradient(
        listOf(tint, Color.Transparent)
    )
    return this.then(Modifier.background(brush))
}
