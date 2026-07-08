package com.example.displayapp.presentation.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.EvGreen

/**
 * Branded header used on the Drive screen.
 *
 * Layout:
 *   ┌───────────────────────────────────────────┐
 *   │ Telemetry                  🔵        ⚙   │
 *   └───────────────────────────────────────────┘
 *
 * - The Bluetooth indicator turns green when the link is up and gray (with a
 *   "disconnect" glyph variant) when down.
 * - Gear opens Settings.
 *
 * Designed to sit flush with the status bar — minimal vertical footprint, no
 * Material IconButton padding around the actions (uses tighter 36dp hit targets).
 */
@Composable
fun BrandHeader(
    connectionState: ConnectionState,
    wifiConnected: Boolean,
    onConnectTap: () -> Unit,
    onSettingsTap: () -> Unit,
    onBluetoothLongPress: () -> Unit = onConnectTap,
    bluetoothAnchor: @Composable (Modifier) -> Unit = { _ -> },
    hotspotActive: Boolean = false,
    onHotspotTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bluetoothConnected = connectionState == ConnectionState.CONNECTED

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BrandMark()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Hotspot state — green while the phone's soft AP is on (the board
            // joins it for map downloads). Tap → tethering settings.
            StatusIcon(
                icon = EvIcons.Hotspot,
                connected = hotspotActive,
                contentDescription = if (hotspotActive) "Hotspot on" else "Hotspot off",
                onClick = onHotspotTap
            )
            // Bluetooth icon has two gestures + an anchored slot for the popover.
            // Tap → small popover with status + quick action.
            // Long-press → BluetoothQuickSheet (full management).
            Box {
                StatusIcon(
                    icon = if (bluetoothConnected) EvIcons.Bluetooth else EvIcons.BluetoothOff,
                    connected = bluetoothConnected,
                    contentDescription = if (bluetoothConnected) "Bluetooth connected" else "Bluetooth disconnected",
                    onClick = onConnectTap,
                    onLongClick = onBluetoothLongPress
                )
                // The popover (a DropdownMenu) anchors to this Box, so its
                // owner is the Bluetooth icon's coordinates.
                bluetoothAnchor(Modifier)
            }
            StatusIcon(
                icon = EvIcons.Settings,
                connected = false,
                tintOverride = MaterialTheme.colorScheme.onSurface,
                contentDescription = "Settings",
                onClick = onSettingsTap
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Brand mark                                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun BrandMark() {
    Text(
        text = "Drive Telemetry",
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp
    )
}

/* -------------------------------------------------------------------------- */
/*  Status icon                                                               */
/* -------------------------------------------------------------------------- */

/**
 * Compact tappable status indicator. Smaller than [androidx.compose.material3.IconButton]
 * (which has a hard 48dp size) so multiple indicators fit in a tight header row.
 *
 * - 36dp hit target — meets accessibility minimum without bloating the header.
 * - Green when [connected], muted on-surface-variant otherwise.
 * - [tintOverride] short-circuits the green/gray logic (used for the Settings gear).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusIcon(
    icon: ImageVector,
    connected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    tintOverride: Color? = null,
    onLongClick: (() -> Unit)? = null
) {
    val target = tintOverride
        ?: if (connected) EvGreen
        else MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(targetValue = target, label = "status-tint")
    val haptic = LocalHapticFeedback.current

    // Tight 30dp hit target — keeps the whole header row close to the height of
    // the wordmark itself, so there's no apparent gap above the text.
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
