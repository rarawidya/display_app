package com.example.displayapp.presentation.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.displayapp.presentation.ui.common.GlassCard
import com.example.displayapp.presentation.ui.common.SectionHeader
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim

/**
 * Placeholder detail screen reachable from a Logs tap.
 *
 * Wired into the nav graph (route "trip/{tripId}") so the navigation pattern
 * is in place — when the team is ready to add per-trip charts, just replace
 * this body. The trip id is already routed in.
 *
 * Hides the bottom bar (handled in [AppNavHost]) for a more focused detail view.
 */
@Composable
fun TripDetailScreen(
    tripId: Long,
    onBack: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Dim.screenGutter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dim.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = EvIcons.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.padding(start = Dim.sm))
                Text(
                    text = "Trip #$tripId",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(Dim.lg))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
                    SectionHeader(title = "Coming soon")
                    Text(
                        text = "Per-trip telemetry charts and replay will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "🛣",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
