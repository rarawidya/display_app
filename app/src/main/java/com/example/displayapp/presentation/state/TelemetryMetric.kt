package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvCyan
import com.example.displayapp.ui.theme.EvLime
import com.example.displayapp.ui.theme.EvRed
import com.example.displayapp.ui.theme.EvViolet

@Immutable
enum class TelemetryMetric(
    val displayName: String,
    val unit: String,
    val color: Color,
    val format: String,
    val fixedRange: ClosedFloatingPointRange<Float>? = null
) {
    Speed(       "Speed",       "km/h", EvCyan,   "%.0f"),
    Voltage(     "Voltage",     "V",    EvLime,   "%.1f"),
    Current(     "Current",     "A",    EvAmber,  "%.1f"),
    Temperature( "Temperature", "°C",   EvRed,    "%.0f"),
    Battery(     "Battery",     "%",    EvViolet, "%.0f", 0f..100f)
}
