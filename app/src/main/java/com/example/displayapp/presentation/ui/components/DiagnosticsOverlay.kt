package com.example.displayapp.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.presentation.state.DiagnosticsState

/**
 * Semi-transparent diagnostics overlay showing real-time telemetry stats.
 * Toggle via long-press or ViewModel.toggleDiagnostics().
 */
@Composable
fun DiagnosticsOverlay(
    diagnostics: DiagnosticsState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        DiagLine("FPS", "${diagnostics.framesPerSecond}")
        DiagLine("Frames", "${diagnostics.framesDecoded}")
        DiagLine("CRC Err", "${diagnostics.crcErrors}")
        DiagLine("Sync Loss", "${diagnostics.syncLosses}")
        DiagLine("Reconnects", "${diagnostics.reconnects}")
        if (diagnostics.notificationsPushed > 0 || diagnostics.notificationsDropped > 0) {
            DiagLine("Notif ✓/✗", "${diagnostics.notificationsPushed}/${diagnostics.notificationsDropped}")
        }
        if (diagnostics.lastUpdateMs > 0) {
            val age = System.currentTimeMillis() - diagnostics.lastUpdateMs
            DiagLine("Data Age", "${age}ms")
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFF00E676),
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
