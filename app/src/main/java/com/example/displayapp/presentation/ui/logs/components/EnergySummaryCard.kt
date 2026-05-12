package com.example.displayapp.presentation.ui.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.data.energy.EnergyCostCalculator
import com.example.displayapp.data.energy.EnergyFormatter
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen

/**
 * Trip-level energy analytics card. Sits between the hero summary and the
 * raw telemetry charts.
 *
 * Visible rows:
 *  - Energy Used (Wh / kWh, auto-switching)
 *  - Energy Recovered (green accent, regen)
 *  - Net Energy
 *  - Efficiency (Wh/km)
 *  - Cost (hidden when [ratePerKwh] is null — Phase 1 always null)
 *
 * Honesty rule: when [energyUsedWh] and [energyRegenWh] are both 0, the trip
 * predates the v2 migration. Show "—" rather than back-filling with a
 * heuristic — the user knows the data isn't there.
 */
@Composable
fun EnergySummaryCard(
    energyUsedWh: Double,
    energyRegenWh: Double,
    distanceMeters: Long,
    ratePerKwh: Float? = null,
    modifier: Modifier = Modifier
) {
    val hasData = energyUsedWh > 0.0 || energyRegenWh > 0.0
    val netWh = energyUsedWh - energyRegenWh
    val distanceKm = distanceMeters / 1000f
    val whPerKm: Float? = if (hasData && distanceKm > 0.05f) (netWh / distanceKm).toFloat() else null
    val costLabel = if (hasData) EnergyCostCalculator.formatCost(netWh, ratePerKwh) else null

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.md)) {

            // Header — same visual rhythm as the Hero card so the two cards read as a pair.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "ENERGY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (hasData) EnergyFormatter.formatEnergyWh(energyUsedWh) else "—",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "consumed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(EvAmber.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = EvIcons.Plug,
                        contentDescription = null,
                        tint = EvAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Stat grid. 2×2 lays the four primary numbers out in a Tesla-trip-summary
            // pattern: Recovered + Net on top, Efficiency + Cost below.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dim.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatBlock(
                    label = "RECOVERED",
                    value = if (hasData) EnergyFormatter.formatEnergyWh(energyRegenWh) else "—",
                    accent = EvGreen
                )
                StatBlock(
                    label = "NET",
                    value = if (hasData) EnergyFormatter.formatNetEnergyWh(netWh) else "—",
                    accent = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatBlock(
                    label = "EFFICIENCY",
                    value = EnergyFormatter.formatEfficiency(whPerKm),
                    suffix = if (whPerKm != null) "Wh/km" else null,
                    accent = EvBlue
                )
                if (costLabel != null) {
                    StatBlock(
                        label = "COST",
                        value = costLabel,
                        accent = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    // Placeholder column keeps the row's spacing balanced even when
                    // cost is hidden — no visible label, just blank.
                    Spacer(Modifier.size(0.dp))
                }
            }
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    accent: Color,
    suffix: String? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
            if (suffix != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}
