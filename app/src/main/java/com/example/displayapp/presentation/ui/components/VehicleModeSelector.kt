package com.example.displayapp.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.domain.model.VehicleMode

@Composable
fun VehicleModeSelector(
    currentMode: VehicleMode,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VehicleMode.entries.forEach { mode ->
            val isActive = mode == currentMode
            val color = if (isActive) modeColor(mode) else Color.Transparent
            val textColor = if (isActive) {
                Color.Black
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = color,
                tonalElevation = if (isActive) 4.dp else 0.dp
            ) {
                Text(
                    text = mode.name.first().toString(),
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun modeColor(mode: VehicleMode): Color = when (mode) {
    VehicleMode.PARK -> Color(0xFF90A4AE)
    VehicleMode.ECO -> Color(0xFF00E676)
    VehicleMode.NORMAL -> Color(0xFF2979FF)
    VehicleMode.SPORT -> Color(0xFFFF1744)
}
