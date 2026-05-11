package com.example.displayapp.presentation.ui.charts

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.displayapp.presentation.state.ChartsUiState
import com.example.displayapp.presentation.state.TelemetryMetric
import com.example.displayapp.presentation.state.TimeRange
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.ui.theme.Dim

@Composable
fun ChartsFullscreen(
    state: ChartsUiState,
    onClose: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    onToggleMetric:  (TelemetryMetric) -> Unit,
    onFocusMetric:   (TelemetryMetric) -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false
        )
    ) {
        BackHandler(onBack = onClose)
        LockLandscapeAndImmersive()
        FullscreenChartLayout(
            state = state,
            onClose = onClose,
            onRangeSelected = onRangeSelected,
            onToggleMetric = onToggleMetric,
            onFocusMetric  = onFocusMetric
        )
    }
}

@Composable
private fun LockLandscapeAndImmersive() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val priorOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val window = activity?.window
        val insetsController = window?.insetsController

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = priorOrientation
            insetsController?.show(WindowInsets.Type.systemBars())
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun FullscreenChartLayout(
    state: ChartsUiState,
    onClose: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    onToggleMetric:  (TelemetryMetric) -> Unit,
    onFocusMetric:   (TelemetryMetric) -> Unit
) {
    val currentRange = TimeRange.entries.firstOrNull { it.seconds == state.rangeSec } ?: TimeRange.SEC_60

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Dim.lg, vertical = Dim.md),
        verticalArrangement = Arrangement.spacedBy(Dim.md)
    ) {
        TopToolbar(
            state = state,
            currentRange = currentRange,
            onClose = onClose,
            onRangeSelected = onRangeSelected
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(Dim.cardCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(Dim.lg)
        ) {
            RealtimeLineChart(
                state = state,
                modifier = Modifier.fillMaxSize()
            )
        }
        MetricToggleChips(
            state = state,
            onToggle = onToggleMetric,
            onFocus  = onFocusMetric,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TopToolbar(
    state: ChartsUiState,
    currentRange: TimeRange,
    onClose: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit
) {
    val metric = state.focusedMetric
    val latest = state.latestFor(metric)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            CloseButton(onClose)
            FocusInline(metric = metric, latest = latest)
        }
        TimeRangeSelector(
            selected = currentRange,
            onSelected = onRangeSelected
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = EvIcons.FullscreenExit,
            contentDescription = "Exit fullscreen",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun FocusInline(metric: TelemetryMetric, latest: Float?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(metric.color))
        Text(
            text = "FOCUS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = metric.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(Dim.xs))
        Text(
            text = if (latest != null) metric.format.format(latest) else "—",
            style = MaterialTheme.typography.titleLarge,
            color = metric.color
        )
        Text(
            text = metric.unit,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
