package com.example.displayapp.presentation.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.data.format.Formatters
import com.example.displayapp.data.sharing.ShareHelper
import com.example.displayapp.domain.model.TimeFormat
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.presentation.state.TripDetailUiState
import com.example.displayapp.presentation.state.TripFaultRow
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.components.mode.ModeBadge
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.logs.components.EnergySummaryCard
import com.example.displayapp.presentation.ui.logs.components.StaticTelemetryChart
import com.example.displayapp.presentation.viewmodel.TripDetailViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvRed
import com.example.displayapp.ui.theme.seriesColor
import java.io.File

/**
 * Full Trip Detail screen.
 *
 * Layout (top→bottom):
 *   1. App bar (back + title + share/delete actions)
 *   2. Hero summary card  (date, duration, distance, avg/max speed, battery delta, energy)
 *   3. Speed chart        (km/h)
 *   4. Voltage chart      (V)
 *   5. Current chart      (A)
 *   6. Temperature chart  (°C)
 *
 * Loading + not-found states render in place of the body so the screen never
 * shows half-data. Loading uses the existing trip-id while the VM hydrates.
 */
@Composable
fun TripDetailScreen(
    tripId: Long,
    viewModel: TripDetailViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(tripId) { viewModel.load(tripId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    TripDetailContent(
        state = state,
        onBack = onBack,
        onExport = viewModel::export,
        onDelete = {
            viewModel.delete { onBack() }
        },
        onExportConsumed = viewModel::consumeExportResult
    )
}

@Composable
private fun TripDetailContent(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExportConsumed: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Confirmation dialog gating the actual delete — flipped on by the
    // top-bar Trash button and cleared when the user confirms or cancels.
    var confirmingDelete by remember { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this trip?") },
            text = {
                Text("The trip and all of its telemetry samples will be removed permanently.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    }
                ) { Text("Delete", color = EvRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }

    // Show export feedback Snackbar. The Share action launches the system share sheet.
    LaunchedEffect(state.exportedFilePath, state.exportError) {
        when {
            state.exportError != null -> {
                snackbar.showSnackbar("Export failed: ${state.exportError}")
                onExportConsumed()
            }
            state.exportedFilePath != null -> {
                val result = snackbar.showSnackbar(
                    message = "CSV saved",
                    actionLabel = "Share"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    ShareHelper.shareFile(
                        context = context,
                        file = File(state.exportedFilePath),
                        subject = "EV Trip ${Formatters.shortDate(state.startMs)}"
                    )
                }
                onExportConsumed()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dim.screenGutter)
                    .padding(top = Dim.screenTop, bottom = Dim.xxl),
                verticalArrangement = Arrangement.spacedBy(Dim.md)
            ) {
                val app = LocalAppSettings.current
                val dateLabel = if (state.startMs > 0) Formatters.longDateTime(state.startMs, app.timeFormat) else ""
                DetailTopBar(
                    title = if (state.notFound) "Trip not found" else dateLabel.ifBlank { "Trip" },
                    isActive = state.isActive,
                    canActOnTrip = !state.loading && !state.notFound,
                    exportInProgress = state.exportInProgress,
                    onBack = onBack,
                    onExport = onExport,
                    onDelete = { confirmingDelete = true }
                )

                when {
                    state.loading  -> LoadingBlock()
                    state.notFound -> NotFoundBlock()
                    else           -> DetailBody(state = state)
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Dim.lg)
            ) { data -> Snackbar(snackbarData = data) }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Top bar                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun DetailTopBar(
    title: String,
    isActive: Boolean,
    canActOnTrip: Boolean,
    exportInProgress: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = EvIcons.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (isActive) {
                    Text(
                        text = "Recording…",
                        style = MaterialTheme.typography.bodySmall,
                        color = EvAmber
                    )
                }
            }
        }
        if (canActOnTrip) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onExport, enabled = !exportInProgress) {
                    if (exportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = EvIcons.Download,
                            contentDescription = "Export CSV",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = EvIcons.Trash,
                        contentDescription = "Delete trip",
                        tint = EvRed
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  States                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NotFoundBlock() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(Dim.lg)
        ) {
            Text(
                text = "Trip not found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "It may have been deleted or the database is out of sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Body                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun DetailBody(state: TripDetailUiState) {
    val app = LocalAppSettings.current
    HeroSummaryCard(state = state)

    // Energy analytics (Used / Recovered / Net / Efficiency). Real now that
    // `currentMotor` is calibrated signed deci-amps → energy = ∫V·I·dt is persisted
    // per trip; shows "—" only for pre-energy rows.
    EnergySummaryCard(
        energyUsedWh = state.energyUsedWh,
        energyRegenWh = state.energyRegenWh,
        distanceMeters = state.distanceMeters,
        ratePerKwh = state.ratePerKwh,
    )

    // Faults recorded during the ride (over-temp, low battery, controller
    // faults). Hidden entirely for a clean ride so a fault-free trip reads
    // uncluttered; only surfaces when something actually happened.
    if (state.faults.isNotEmpty()) {
        FaultSection(faults = state.faults, timeFormat = app.timeFormat)
    }

    // Trip Detail charts only real controller channels (capnp.md). Current & Power
    // are back now that `currentMotor` (@1) is calibrated signed deci-amps. Battery
    // Temp stays absent from the v1 wire, so its chart is still omitted.
    // NOTE: trips recorded before the current calibration persisted raw-count current,
    // so their Current/Power series reflect that historical (unscaled) data — the
    // decode is intentionally decoupled from the wire, so old rows are not rewritten.
    // Series are SI (km/h, °C); conversion is applied at label time via
    // displayConverter so toggling km/h↔mph updates labels without rebuilding data.
    val speedConverter: (Float) -> Float = { app.speedUnit.convertFromKmh(it) }
    val tempConverter: (Float) -> Float = { app.temperatureUnit.convertFromCelsius(it) }
    StaticTelemetryChart(
        title = TelemetryMetric.Speed.displayName,
        unit = app.speedUnit.suffix,
        series = state.speedSeries,
        color = TelemetryMetric.Speed.seriesColor(),
        latestFormat = TelemetryMetric.Speed.format,
        displayConverter = speedConverter
    )
    StaticTelemetryChart(
        title = TelemetryMetric.Voltage.displayName,
        unit = TelemetryMetric.Voltage.unit,
        series = state.voltageSeries,
        color = TelemetryMetric.Voltage.seriesColor(),
        latestFormat = TelemetryMetric.Voltage.format
    )
    StaticTelemetryChart(
        title = TelemetryMetric.Current.displayName,
        unit = TelemetryMetric.Current.unit,
        series = state.currentSeries,
        color = TelemetryMetric.Current.seriesColor(),
        latestFormat = TelemetryMetric.Current.format
    )
    StaticTelemetryChart(
        title = TelemetryMetric.Power.displayName,
        unit = TelemetryMetric.Power.unit,
        series = state.powerSeries,
        color = TelemetryMetric.Power.seriesColor(),
        latestFormat = TelemetryMetric.Power.format
    )
    StaticTelemetryChart(
        title = TelemetryMetric.Battery.displayName,
        unit = TelemetryMetric.Battery.unit,
        series = state.batterySeries,
        color = TelemetryMetric.Battery.seriesColor(),
        latestFormat = TelemetryMetric.Battery.format
    )
    StaticTelemetryChart(
        title = TelemetryMetric.MotorTemp.displayName,
        unit = app.temperatureUnit.suffix,
        series = state.temperatureSeries,
        color = TelemetryMetric.MotorTemp.seriesColor(),
        latestFormat = TelemetryMetric.MotorTemp.format,
        displayConverter = tempConverter
    )
    StaticTelemetryChart(
        title = TelemetryMetric.ControllerTemp.displayName,
        unit = app.temperatureUnit.suffix,
        series = state.controllerTempSeries,
        color = TelemetryMetric.ControllerTemp.seriesColor(),
        latestFormat = TelemetryMetric.ControllerTemp.format,
        displayConverter = tempConverter
    )
}

/* -------------------------------------------------------------------------- */
/*  Faults                                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun FaultSection(faults: List<TripFaultRow>, timeFormat: TimeFormat) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dim.sm)
            ) {
                Icon(
                    imageVector = EvIcons.Warning,
                    contentDescription = null,
                    tint = EvAmber,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Faults",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = faults.size.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            faults.forEach { FaultRow(fault = it, timeFormat = timeFormat) }
        }
    }
}

@Composable
private fun FaultRow(fault: TripFaultRow, timeFormat: TimeFormat) {
    val (accent, icon) = when (fault.severity) {
        2 -> EvRed to EvIcons.Warning
        1 -> EvAmber to EvIcons.Warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant to EvIcons.InfoOutline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = fault.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = Formatters.time(fault.timestampMs, timeFormat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeroSummaryCard(state: TripDetailUiState) {
    val app = LocalAppSettings.current
    val distanceLabel = app.speedUnit.formatDistance(state.distanceMeters)
    val durationLabel = formatDuration(state.durationSec)
    val avgSpeedLabel = app.speedUnit.formatSpeed(state.avgSpeedKmh10 / 10f)
    val maxSpeedLabel = app.speedUnit.formatSpeed(state.maxSpeedKmh10 / 10f)
    val batteryLabel = "${state.startBattery}% → ${state.endBattery?.toString() ?: "—"}%"

    val speedColor   = TelemetryMetric.Speed.seriesColor()
    val batteryColor = TelemetryMetric.Battery.seriesColor()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "TRIP SUMMARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = distanceLabel,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Drive icon + mode badge. The badge mirrors the dominant
                // mode of this recorded trip so a glance at the header tells
                // the user what kind of drive they're inspecting.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(speedColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = EvIcons.Drive,
                            contentDescription = null,
                            tint = speedColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    ModeBadge(mode = state.dominantMode)
                }
            }

            Spacer(Modifier.height(0.dp))

            // Speed + battery headline stats; energy/efficiency live in the
            // EnergySummaryCard below (all wire-backed via the calibrated current channel).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(label = "AVG", value = avgSpeedLabel, accent = speedColor)
                Stat(label = "MAX", value = maxSpeedLabel, accent = speedColor)
                Stat(label = "BATT", value = batteryLabel, accent = batteryColor)
            }
        }
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

@Composable
private fun Stat(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}
