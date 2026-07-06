package com.example.displayapp.presentation.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.CarbonOnVariant
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed

/**
 * Cluster-style telltale strip — mirrors the physical dashboard's warning-lamp
 * row. Each lamp lights in its semantic accent when its VotolTelemetry
 * `flags`/`faultCode` bit is set, and sits dimmed otherwise so the strip is a
 * stable legend (like a real instrument cluster) rather than popping in and out.
 *
 * v1 wire exposes engineRunning / brake / reverse (flags) + a fault bitfield;
 * turn-signals / high-beam / battery / thermal lamps arrive when firmware adds
 * their channels (telemetry.capnp @11+).
 */
@Composable
fun TelltaleRow(state: DashboardUiState, modifier: Modifier = Modifier) {
    // Nudge the card border red the moment a fault is present.
    val accent = if (state.faultActive) EvRed else null

    GlassCard(modifier = modifier, contentPadding = Dim.md, accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Telltale("READY", EvIcons.Bolt, EvGreen, state.engineRunning)
            Telltale("BRAKE", EvIcons.Brake, EvAmber, state.brakeActive)
            Telltale("REV", EvIcons.ArrowBack, EvBlue, state.reverseActive)
            Telltale(
                label = if (state.faultActive) "FAULT" else "OK",
                icon = EvIcons.Warning,
                activeColor = EvRed,
                active = state.faultActive
            )
        }
    }
}

@Composable
private fun Telltale(
    label: String,
    icon: ImageVector,
    activeColor: Color,
    active: Boolean
) {
    val tint = if (active) activeColor else CarbonOnVariant.copy(alpha = 0.30f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xxs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (active) "$label active" else "$label inactive",
            tint = tint,
            modifier = Modifier.size(Dim.xl)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
