package com.innodrive.evdash.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.innodrive.evdash.domain.model.ConnectionState

@Composable
fun ConnectionStatusBar(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    val (color, label) = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFFFF1744) to "Disconnected"
        ConnectionState.SCANNING -> Color(0xFFFFAB00) to "Scanning..."
        ConnectionState.CONNECTING -> Color(0xFFFFAB00) to "Connecting..."
        ConnectionState.CONNECTED -> Color(0xFF00E676) to "Connected"
        ConnectionState.RECONNECTING -> Color(0xFFFFAB00) to "Reconnecting..."
    }

    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state == ConnectionState.SCANNING || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = color
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}
