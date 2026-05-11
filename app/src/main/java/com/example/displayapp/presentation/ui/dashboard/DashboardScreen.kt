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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.example.displayapp.presentation.state.batteryAlertLevel
import com.example.displayapp.presentation.state.temperatureAlertLevel
import com.example.displayapp.presentation.ui.components.DiagnosticsOverlay
import com.example.displayapp.presentation.ui.components.PremiumMetricTile
import com.example.displayapp.presentation.ui.components.SpeedometerGauge
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
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
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()

    DashboardContent(
        state = uiState,
        wifiConnected = wifiConnected,
        showDiagnostics = showDiagnostics,
        onConnectionTap = onConnectionTap,
        onSettingsTap = onSettingsTap,
        onToggleDiagnostics = viewModel::toggleDiagnostics
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardContent(
    state: DashboardUiState,
    wifiConnected: Boolean = false,
    showDiagnostics: Boolean = false,
    onConnectionTap: () -> Unit = {},
    onSettingsTap: () -> Unit = {},
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
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                if (maxWidth >= 720.dp) {
                    DashboardLandscape(state, wifiConnected, onConnectionTap, onSettingsTap)
                } else {
                    DashboardPortrait(state, wifiConnected, onConnectionTap, onSettingsTap)
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
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.screenGutter)
            .padding(top = 0.dp, bottom = Dim.lg),
        verticalArrangement = Arrangement.spacedBy(Dim.md)
    ) {
        BrandHeader(
            connectionState = state.connectionState,
            wifiConnected = wifiConnected,
            onConnectTap = onConnectionTap,
            onSettingsTap = onSettingsTap
        )
        SpeedometerSection(state = state)
        VehicleRowCard(name = "GESITS", trim = "G-1")
        TelemetryGrid(state = state)
        Spacer(Modifier.height(Dim.sm))
    }
}

/* -------------------------------------------------------------------------- */
/*  Tablet / landscape layout                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun DashboardLandscape(
    state: DashboardUiState,
    wifiConnected: Boolean,
    onConnectionTap: () -> Unit,
    onSettingsTap: () -> Unit
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
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                VehicleRowCard(name = "GESITS", trim = "G-1")
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
/*  Telemetry grid — 2×2 of Battery / Power / Current / Voltage                */
/* -------------------------------------------------------------------------- */

@Composable
private fun TelemetryGrid(state: DashboardUiState) {
    val voltage = state.voltage.toFloatOrNull() ?: 0f
    val current = state.current.toFloatOrNull() ?: 0f
    val power   = voltage * current  // derived metric — not in state model

    Column(verticalArrangement = Arrangement.spacedBy(Dim.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.md)) {
            PremiumMetricTile(
                icon = EvIcons.Battery,
                label = "Battery",
                value = state.batteryPercent.toFloat(),
                unit = "%",
                fractionDigits = 1,
                level = batteryAlertLevel(state.batteryPercent),
                modifier = Modifier.weight(1f)
            )
            PremiumMetricTile(
                icon = EvIcons.Plug,
                label = "Power",
                value = power,
                unit = "Watt",
                fractionDigits = 1,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.md)) {
            PremiumMetricTile(
                icon = EvIcons.Bolt,
                label = "Current",
                value = current,
                unit = "A",
                fractionDigits = 1,
                modifier = Modifier.weight(1f)
            )
            PremiumMetricTile(
                icon = EvIcons.Bolt,
                label = "Voltage",
                value = voltage,
                unit = "V",
                fractionDigits = 1,
                modifier = Modifier.weight(1f)
            )
        }
        // Secondary tiles kept for diagnostics (motor temp + odo)
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.md)) {
            PremiumMetricTile(
                icon = EvIcons.Thermo,
                label = "Controller",
                value = state.controllerTemperature.toFloat(),
                unit = "°C",
                level = temperatureAlertLevel(state.controllerTemperature),
                modifier = Modifier.weight(1f)
            )
            PremiumMetricTile(
                icon = EvIcons.Speed,
                label = "Odometer",
                valueText = state.odometer,
                unit = "km",
                modifier = Modifier.weight(1f)
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
