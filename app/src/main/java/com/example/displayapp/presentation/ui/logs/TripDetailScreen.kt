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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.displayapp.presentation.state.TripDetailUiState
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.logs.components.EnergySummaryCard
import com.example.displayapp.presentation.ui.logs.components.StaticTelemetryChart
import com.example.displayapp.presentation.viewmodel.TripDetailViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvRed
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
                    onDelete = onDelete
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
                        imageVector = EvIcons.MoreVert,
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

    EnergySummaryCard(
        energyUsedWh = state.energyUsedWh,
        energyRegenWh = state.energyRegenWh,
        distanceMeters = state.distanceMeters,
        ratePerKwh = state.ratePerKwh
    )

    StaticTelemetryChart(
        title = "Speed",
        unit = app.speedUnit.suffix,
        series = state.speedSeries.map { app.speedUnit.convertFromKmh(it) }.toFloatArray(),
        color = EvBlue,
        latestFormat = "%.0f"
    )
    StaticTelemetryChart(
        title = "Voltage",
        unit = "V",
        series = state.voltageSeries,
        color = EvLime,
        latestFormat = "%.1f"
    )
    StaticTelemetryChart(
        title = "Current",
        unit = "A",
        series = state.currentSeries,
        color = EvAmber,
        latestFormat = "%.1f"
    )
    StaticTelemetryChart(
        title = "Temperature",
        unit = app.temperatureUnit.suffix,
        series = state.temperatureSeries.map { app.temperatureUnit.convertFromCelsius(it) }.toFloatArray(),
        color = EvRed,
        latestFormat = "%.0f"
    )
}

@Composable
private fun HeroSummaryCard(state: TripDetailUiState) {
    val app = LocalAppSettings.current
    val distanceLabel = app.speedUnit.formatDistance(state.distanceMeters)
    val durationLabel = formatDuration(state.durationSec)
    val avgSpeedLabel = app.speedUnit.formatSpeed(state.avgSpeedKmh10 / 10f)
    val maxSpeedLabel = app.speedUnit.formatSpeed(state.maxSpeedKmh10 / 10f)
    val batteryLabel = "${state.startBattery}% → ${state.endBattery?.toString() ?: "—"}%"

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
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(EvBlue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = EvIcons.Drive,
                        contentDescription = null,
                        tint = EvBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(0.dp))

            // Pairs of metrics — keeps the card compact while showing everything.
            // ENERGY moved to its own EnergySummaryCard below the hero.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(label = "AVG", value = avgSpeedLabel, accent = EvBlue)
                Stat(label = "MAX", value = maxSpeedLabel, accent = EvLime)
                Stat(label = "BATT", value = batteryLabel, accent = EvAmber)
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
