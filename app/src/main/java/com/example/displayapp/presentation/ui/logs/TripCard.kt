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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.displayapp.presentation.state.TripRow
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.StatusChip
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvCyan
import com.example.displayapp.ui.theme.EvLime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())

@Composable
fun TripCard(
    row: TripRow,
    onClick: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        text = dateFmt.format(Date(row.startMs)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${row.distanceLabel}  ·  ${row.durationLabel}",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat("AVG", row.avgSpeedLabel, EvCyan)
                MiniStat("MAX", row.maxSpeedLabel, EvLime)
                MiniStat("BATT", row.batteryLabel, EvAmber)
                MiniStat("ENERGY", row.energyLabel, EvCyan)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
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
