package com.example.displayapp.presentation.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvBlueDeep
import com.example.displayapp.ui.theme.EvGreen

/**
 * Branded header used on the Drive screen.
 *
 * Layout:
 *   ┌───────────────────────────────────────────┐
 *   │ ◯ Display**App**       📶  🔵        ⚙   │
 *   └───────────────────────────────────────────┘
 *
 * - "App" is colored with the brand primary; "Display" uses on-surface text.
 * - The Bluetooth + Wi-Fi indicators each turn green when their channel is up
 *   and gray (with a "disconnect" glyph variant) when down.
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
            StatusIcon(
                icon = if (wifiConnected) EvIcons.Wifi else EvIcons.WifiOff,
                connected = wifiConnected,
                contentDescription = if (wifiConnected) "Wi-Fi connected" else "Wi-Fi disconnected",
                onClick = { /* no-op — system Wi-Fi isn't user-managed from here */ }
            )
            StatusIcon(
                icon = if (bluetoothConnected) EvIcons.Bluetooth else EvIcons.BluetoothOff,
                connected = bluetoothConnected,
                contentDescription = if (bluetoothConnected) "Bluetooth connected" else "Bluetooth disconnected",
                onClick = onConnectTap
            )
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(EvBlueDeep, EvBlue))
                )
        )
        val wordmark = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            ) { append("Display") }
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            ) { append("App") }
        }
        Text(text = wordmark, fontSize = 19.sp)
    }
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
@Composable
private fun StatusIcon(
    icon: ImageVector,
    connected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    tintOverride: Color? = null
) {
    val target = tintOverride
        ?: if (connected) EvGreen
        else MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(targetValue = target, label = "status-tint")

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
