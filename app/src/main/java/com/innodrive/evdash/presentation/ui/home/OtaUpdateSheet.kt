package com.innodrive.evdash.presentation.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.innodrive.evdash.presentation.ui.icons.EvIcons
import com.innodrive.evdash.ui.theme.Dim
import com.innodrive.evdash.ui.theme.EvBlue
import com.innodrive.evdash.ui.theme.EvGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Steps of the (mock) over-the-air firmware check. */
private enum class OtaPhase { Idle, Checking, UpToDate }

/**
 * Tappable "OTA Update" row that lives at the bottom of the Vehicle Information
 * card and opens the [OtaUpdateSheet]. Self-contained: it owns the sheet-visibility
 * state so the parent only has to place it.
 *
 * The check itself is a placeholder (there is no OTA backend yet) — it validates the
 * end-to-end UX and is the single seam to wire a real update service into later.
 */
@Composable
fun OtaUpdateEntry(
    currentFirmware: String,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { showSheet = true }
            .padding(vertical = Dim.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.md)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(EvBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = EvIcons.Download,
                contentDescription = null,
                tint = EvBlue,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "OTA Update",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Firmware $currentFirmware",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = EvIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

    if (showSheet) {
        OtaUpdateSheet(
            currentFirmware = currentFirmware,
            connected = connected,
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtaUpdateSheet(
    currentFirmware: String,
    connected: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phase by remember { mutableStateOf(OtaPhase.Idle) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(EvBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = EvIcons.Download,
                        contentDescription = null,
                        tint = EvBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text("OTA Update", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Over-the-air firmware",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            InfoLine("Current firmware", currentFirmware)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Status region
            when {
                !connected -> StatusText(
                    "Connect your vehicle to check for and install firmware updates."
                )
                phase == OtaPhase.Checking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    StatusText("Checking for updates…")
                }
                phase == OtaPhase.UpToDate -> Column {
                    Text(
                        "You're up to date",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EvGreen
                    )
                    Spacer(Modifier.height(4.dp))
                    StatusText("$currentFirmware is the latest available firmware.")
                }
                else -> StatusText("Check for the latest controller firmware over the air.")
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    when (phase) {
                        OtaPhase.UpToDate -> onDismiss()
                        else -> {
                            phase = OtaPhase.Checking
                            scope.launch {
                                delay(1_800) // placeholder for the real OTA check
                                phase = OtaPhase.UpToDate
                            }
                        }
                    }
                },
                enabled = connected && phase != OtaPhase.Checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        !connected -> "Vehicle offline"
                        phase == OtaPhase.Checking -> "Checking…"
                        phase == OtaPhase.UpToDate -> "Done"
                        else -> "Check for Updates"
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
