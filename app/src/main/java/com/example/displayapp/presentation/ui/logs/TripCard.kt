package com.example.displayapp.presentation.ui.logs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.displayapp.data.format.Formatters
import com.example.displayapp.domain.repository.TripRepository
import com.example.displayapp.presentation.state.TripRow
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.common.StatusChip
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.logs.components.MiniSparkline
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvLime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trip list row.
 *
 *   ┌────────────────────────────────────────────────┐
 *   │ MMM 12 · 14:33               [Export ⬇]        │
 *   │ 12.4 km · 18m 04s                              │
 *   │ ━━━━━━━━━━━ speed sparkline ━━━━━━━━━━━━━━━    │
 *   │ AVG 28 km/h · MAX 52 km/h · BATT 82→47 · ⚡1.2 │
 *   └────────────────────────────────────────────────┘
 *
 * The sparkline is lazy-loaded via [produceState] keyed on tripId — only
 * visible LazyColumn items trigger a downsampled DB read. The query is fast
 * (indexed) and the result is cached for the lifetime of the row composable.
 */
@Composable
fun TripCard(
    row: TripRow,
    tripRepository: TripRepository,
    onClick: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = LocalAppSettings.current
    // Re-format labels from raw values using the live unit prefs.
    val dateLabel = Formatters.dateTime(row.startMs, app.timeFormat)
    val distanceLabel = app.speedUnit.formatDistance(row.distanceMeters)
    val avgSpeedLabel = app.speedUnit.formatSpeed(row.avgSpeedKmh10 / 10f)
    val maxSpeedLabel = app.speedUnit.formatSpeed(row.maxSpeedKmh10 / 10f)

    GlassCard(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        interactive = true,
        accent = if (row.isActive) EvAmber else null
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$distanceLabel  ·  ${row.durationLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.isActive) {
                    StatusChip(text = "Recording", color = EvAmber)
                } else {
                    IconButton(onClick = onExport) {
                        Icon(
                            imageVector = EvIcons.Download,
                            contentDescription = "Export CSV",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Lazy sparkline — see produceState block below for the load policy.
            val sparkline: FloatArray by produceState(initialValue = FloatArray(0), key1 = row.id) {
                value = withContext(Dispatchers.IO) {
                    // Aim for ~30 visual points regardless of trip length.
                    val target = 30
                    val sampleEvery = (row.durationSec * 20 / target).toInt().coerceAtLeast(1)
                    val samples = try {
                        tripRepository.getDownsampledTelemetry(row.id, sampleEvery)
                    } catch (_: Throwable) { emptyList() }
                    FloatArray(samples.size) { samples[it].speed / 10f }
                }
            }
            MiniSparkline(
                series = sparkline,
                color = EvBlue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat("AVG", avgSpeedLabel, EvBlue)
                MiniStat("MAX", maxSpeedLabel, EvLime)
                MiniStat("BATT", row.batteryLabel, EvAmber)
                MiniStat("ENERGY", row.energyLabel, EvGreen)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}
