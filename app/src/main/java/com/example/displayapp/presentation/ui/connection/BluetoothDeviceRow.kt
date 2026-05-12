package com.example.displayapp.presentation.ui.connection

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.displayapp.presentation.state.UiDevice
import com.example.displayapp.presentation.state.UiDeviceState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed

/**
 * One device row inside the Bluetooth quick sheet.
 *
 * Touch target is 72 dp — larger than the Material spec because drivers tap
 * these while glancing away from the road. State drives both the trailing
 * affordance (chip / spinner / chevron) and a left-edge tint stripe.
 */
@Composable
fun BluetoothDeviceRow(
    device: UiDevice,
    secondaryLabel: String? = null,
    showForget: Boolean = false,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    onForget: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isConnected = device.state == UiDeviceState.CONNECTED
    val isConnecting = device.state == UiDeviceState.CONNECTING || device.state == UiDeviceState.RECONNECTING

    val borderColor by animateColorAsState(
        targetValue = when {
            isConnected -> EvGreen.copy(alpha = 0.50f)
            isConnecting -> EvAmber.copy(alpha = 0.50f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "row-border"
    )
    val containerColor = if (isConnected)
        EvGreen.copy(alpha = 0.06f)
    else MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(Dim.lg))
            .border(1.dp, borderColor, RoundedCornerShape(Dim.lg))
            .clickable(enabled = !isConnecting, onClick = onClick),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dim.lg, vertical = Dim.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LeadingBadge(device.state)
            Spacer(Modifier.width(Dim.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondaryLabel ?: rowSecondaryLabel(device),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Dim.sm))
            TrailingAffordance(
                state = device.state,
                signalBars = device.signalBars,
                showForget = showForget,
                onDisconnect = onDisconnect,
                onForget = onForget
            )
        }
    }
}

@Composable
private fun LeadingBadge(state: UiDeviceState) {
    val tint = when (state) {
        UiDeviceState.CONNECTED -> EvGreen
        UiDeviceState.CONNECTING, UiDeviceState.RECONNECTING -> EvAmber
        UiDeviceState.AVAILABLE -> EvBlue
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = EvIcons.Bluetooth,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TrailingAffordance(
    state: UiDeviceState,
    signalBars: Int?,
    showForget: Boolean,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    when (state) {
        UiDeviceState.CONNECTING, UiDeviceState.RECONNECTING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = EvAmber
            )
        }
        UiDeviceState.CONNECTED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectedChip()
                if (showForget) {
                    Spacer(Modifier.width(Dim.xs))
                    OverflowMenu(
                        onDisconnect = onDisconnect,
                        onForget = onForget,
                        showDisconnect = true
                    )
                }
            }
        }
        UiDeviceState.AVAILABLE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (signalBars != null) {
                    SignalBars(level = signalBars)
                    Spacer(Modifier.width(Dim.sm))
                }
                if (showForget) {
                    OverflowMenu(
                        onDisconnect = onDisconnect,
                        onForget = onForget,
                        showDisconnect = false
                    )
                } else {
                    Icon(
                        imageVector = EvIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedChip() {
    Surface(
        shape = RoundedCornerShape(100),
        color = EvGreen.copy(alpha = 0.18f),
        border = androidx.compose.foundation.BorderStroke(1.dp, EvGreen.copy(alpha = 0.40f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(EvGreen)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Connected",
                color = EvGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    showDisconnect: Boolean
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = EvIcons.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showDisconnect) {
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    onClick = { open = false; onDisconnect() }
                )
            }
            DropdownMenuItem(
                text = { Text("Forget device", color = EvRed) },
                onClick = { open = false; onForget() }
            )
        }
    }
}

/**
 * Three vertical bars (Wi-Fi-style) sized by [level] (0..3).
 * Compact enough to fit in the trailing slot of a 72dp row.
 */
@Composable
private fun SignalBars(level: Int) {
    val active = MaterialTheme.colorScheme.onSurface
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.size(width = 16.dp, height = 16.dp)) {
        val barWidth = size.width / 5f
        val gap = barWidth / 2f
        val maxBarHeight = size.height
        repeat(3) { i ->
            val barHeight = maxBarHeight * (0.40f + 0.30f * i)
            val color = if (i < level) active else inactive
            drawRect(
                color = color,
                topLeft = Offset(
                    x = i * (barWidth + gap),
                    y = maxBarHeight - barHeight
                ),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

private fun rowSecondaryLabel(device: UiDevice): String = when (device.state) {
    UiDeviceState.CONNECTING -> "Connecting..."
    UiDeviceState.RECONNECTING -> "Reconnecting..."
    UiDeviceState.CONNECTED -> device.address
    UiDeviceState.AVAILABLE -> device.address
}
