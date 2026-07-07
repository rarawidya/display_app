package com.example.displayapp.presentation.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.data.bluetooth.controller.AdapterState
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed

/**
 * Small dropdown shown when the user single-taps the Bluetooth icon in the
 * Drive header. Mirrors the smartphone quick-toggle pattern: status at a
 * glance + one primary action + an escape hatch to the full sheet.
 *
 * The popover is intentionally lightweight — it doesn't reach into the
 * adapter, scan, or device list. Those live in [BluetoothQuickSheet].
 */
@Composable
fun BluetoothStatusPopover(
    expanded: Boolean,
    adapterState: AdapterState,
    connectionState: ConnectionState,
    connectedName: String?,
    previouslyConnectedName: String?,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSheet: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 240.dp, max = 280.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        StatusBlock(
            adapterState = adapterState,
            connectionState = connectionState,
            connectedName = connectedName,
            previouslyConnectedName = previouslyConnectedName
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val showDisconnect = connectionState == ConnectionState.CONNECTED
        // Don't offer Reconnect while a connect/reconnect is already in flight —
        // tapping it would race a second connect against the active attempt.
        val showReconnect = connectionState != ConnectionState.CONNECTED &&
            connectionState != ConnectionState.CONNECTING &&
            connectionState != ConnectionState.RECONNECTING &&
            previouslyConnectedName != null &&
            adapterState == AdapterState.ON

        if (showDisconnect) {
            ActionButton(
                label = "Disconnect",
                tint = EvRed,
                onClick = { onDismiss(); onDisconnect() }
            )
        } else if (showReconnect) {
            ActionButton(
                label = "Reconnect to $previouslyConnectedName",
                tint = EvBlue,
                onClick = { onDismiss(); onReconnect() }
            )
        }
        ActionButton(
            label = "Manage devices",
            tint = MaterialTheme.colorScheme.onSurface,
            trailingChevron = true,
            onClick = { onDismiss(); onOpenSheet() }
        )
    }
}

@Composable
private fun StatusBlock(
    adapterState: AdapterState,
    connectionState: ConnectionState,
    connectedName: String?,
    previouslyConnectedName: String?
) {
    val (dotColor, titleText, subtitleText) = when {
        adapterState != AdapterState.ON -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Bluetooth off",
            "Long-press to enable"
        )
        connectionState == ConnectionState.CONNECTED -> Triple(
            EvGreen,
            "Connected",
            connectedName ?: "EV controller"
        )
        connectionState == ConnectionState.CONNECTING -> Triple(
            EvAmber,
            "Connecting...",
            previouslyConnectedName ?: "EV controller"
        )
        connectionState == ConnectionState.RECONNECTING -> Triple(
            EvAmber,
            "Reconnecting...",
            previouslyConnectedName ?: "EV controller"
        )
        previouslyConnectedName != null -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Disconnected",
            "Last: $previouslyConnectedName"
        )
        else -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Disconnected",
            "No device paired"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dim.lg, vertical = Dim.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(Dim.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitleText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    trailingChevron: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dim.sm, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = tint,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (trailingChevron) {
                Icon(
                    imageVector = EvIcons.ChevronRight,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
