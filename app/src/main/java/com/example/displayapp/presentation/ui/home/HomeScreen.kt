package com.example.displayapp.presentation.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.R
import com.example.displayapp.data.energy.EnergyFormatter
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.DashboardViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed
import com.example.displayapp.ui.theme.EvViolet
import java.time.LocalTime

/**
 * Home / landing screen — the first cockpit tab.
 *
 * A calm "at rest" overview (mirrors the product mockup): greeting + vehicle
 * name, a hero call-to-action, a compact stats strip, a vehicle-health grid and
 * today's ride summary. It reuses [DashboardViewModel] so every number here is
 * bit-identical to the Drive tab — no second derivation site.
 *
 * Design rules:
 *  - Cards with no real data are omitted, not shown as zeros (see [QuickStatsCard],
 *    [TodaySummaryCard]). Uncalibrated current ⇒ no efficiency/range tile, etc.
 *  - Layout is proportional (Dim tokens) and centered/​capped on wide screens.
 */
@Composable
fun HomeScreen(
    viewModel: DashboardViewModel,
    deviceName: String,
    onStartMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onEditDevice: () -> Unit = onOpenSettings,
    onOpenNotifications: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        deviceName = deviceName,
        onStartMonitoring = onStartMonitoring,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenHistory,
        onOpenBluetooth = onOpenBluetooth,
        onEditDevice = onEditDevice,
        onOpenNotifications = onOpenNotifications
    )
}

@Composable
private fun HomeContent(
    state: DashboardUiState,
    deviceName: String,
    onStartMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onEditDevice: () -> Unit,
    onOpenNotifications: () -> Unit
) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Cap content width on tablets so lines don't stretch uncomfortably.
            val contentWidth = if (maxWidth >= 720.dp) 640.dp else maxWidth
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = contentWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dim.screenGutter)
                    .padding(top = Dim.screenTop, bottom = Dim.xl),
                verticalArrangement = Arrangement.spacedBy(Dim.lg)
            ) {
                GreetingHeader(
                    deviceName = deviceName,
                    connectionState = state.connectionState,
                    onOpenBluetooth = onOpenBluetooth,
                    onEditDevice = onEditDevice,
                    onOpenNotifications = onOpenNotifications,
                    onOpenSettings = onOpenSettings
                )
                HeroCard(
                    connected = connected,
                    onStartMonitoring = onStartMonitoring,
                    onConnect = onOpenBluetooth
                )
                QuickStatsCard(state = state, connected = connected)
                VehicleHealthSection(state = state)
                TodaySummaryCard(state = state, onOpenHistory = onOpenHistory)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Greeting header                                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun GreetingHeader(
    deviceName: String,
    connectionState: ConnectionState,
    onOpenBluetooth: () -> Unit,
    onEditDevice: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            Text(
                text = "${greeting()} 👋",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dim.sm)
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                RoundIcon(
                    icon = EvIcons.Edit,
                    contentDescription = "Rename vehicle",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                    onClick = onEditDevice
                )
            }
            ConnectionLine(connectionState, onOpenBluetooth)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
            SquareIconButton(
                icon = EvIcons.Bell,
                contentDescription = "Notifications",
                onClick = onOpenNotifications
            )
            SquareIconButton(
                icon = EvIcons.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun ConnectionLine(connectionState: ConnectionState, onOpenBluetooth: () -> Unit) {
    val connected = connectionState == ConnectionState.CONNECTED
    val (label, color) = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected" to EvGreen
        ConnectionState.CONNECTING -> "Connecting…" to EvAmber
        ConnectionState.RECONNECTING -> "Reconnecting…" to EvAmber
        else -> "Tap to connect" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onOpenBluetooth)
            .padding(start = Dim.xxs, top = Dim.xxs, end = Dim.xs, bottom = Dim.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (connected) EvIcons.Bluetooth else EvIcons.BluetoothOff,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        // Chevron cue that the row is actionable when there's nothing live yet.
        if (!connected) {
            androidx.compose.material3.Icon(
                imageVector = EvIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Hero call-to-action                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun HeroCard(
    connected: Boolean,
    onStartMonitoring: () -> Unit,
    onConnect: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val heroBrush = Brush.linearGradient(
        listOf(
            primary.copy(alpha = 0.20f),
            primary.copy(alpha = 0.06f)
        )
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp)
            .clip(RoundedCornerShape(Dim.cardCorner))
            .background(MaterialTheme.colorScheme.surface)
            .background(heroBrush)
    ) {
        // Soft blue halo centered behind the motor (matches home.png).
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 8.dp)
                .size(230.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.24f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        Image(
            painter = painterResource(id = R.drawable.hero_motor),
            contentDescription = "Your electric motorcycle",
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterEnd,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .offset(x = 14.dp)
                .padding(vertical = Dim.sm)
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.52f)
                .padding(start = Dim.xl, end = Dim.sm),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Text(
                text = if (connected) "Ready to ride" else "Let's connect",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (connected) "Your scooter is ready whenever you are."
                       else "Pair your scooter over Bluetooth to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dim.xs))
            HeroButton(
                label = if (connected) "Start Monitoring" else "Connect Bluetooth",
                icon = if (connected) EvIcons.ChevronRight else EvIcons.Bluetooth,
                onClick = if (connected) onStartMonitoring else onConnect
            )
        }
    }
}

@Composable
private fun HeroButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dim.md, vertical = Dim.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.xs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                softWrap = false
            )
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Quick stats strip — only cells with real data are rendered.               */
/* -------------------------------------------------------------------------- */

private data class QuickStat(
    val icon: ImageVector,
    val tint: Color,
    val label: String,
    val value: String,
    val unit: String,
    val caption: String,
    val captionColor: Color
)

@Composable
private fun QuickStatsCard(state: DashboardUiState, connected: Boolean) {
    val app = LocalAppSettings.current
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // Every cell here is live telemetry — with no active link there's nothing to
    // show, so the whole strip is omitted rather than rendering stale zeros.
    if (!connected) return
    val stats = buildList {
        if (state.batteryKnown) {
            val pct = state.batteryPercent
            val (cap, capColor) = when {
                pct > 50 -> "Good" to EvGreen
                pct > 20 -> "Fair" to EvAmber
                else -> "Low" to EvRed
            }
            add(QuickStat(EvIcons.Battery, EvGreen, "Battery", "$pct", "%", cap, capColor))
        }
        state.efficiency.rangeKm?.let { km ->
            val v = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH) km * 0.621371f else km
            add(
                QuickStat(
                    EvIcons.Road, EvBlue, "Range",
                    EnergyFormatter.formatRangeKm(v), app.speedUnit.distanceSuffix,
                    "Estimated", onVariant
                )
            )
        }
        // Temperature: prefer a real, non-zero wire channel (controller, then motor).
        val tempC = when {
            state.controllerTemperature > 0 -> state.controllerTemperature
            state.temperature > 0 -> state.temperature
            else -> 0
        }
        if (tempC > 0) {
            val display = app.temperatureUnit.convertFromCelsius(tempC.toFloat())
            val (cap, capColor) = when {
                tempC < 50 -> "Normal" to EvBlue
                tempC < 70 -> "Warm" to EvAmber
                else -> "Hot" to EvRed
            }
            add(
                QuickStat(
                    EvIcons.Thermo, EvViolet, "Temperature",
                    "%.0f".format(display), app.temperatureUnit.suffix, cap, capColor
                )
            )
        }
        if (state.connectionState == ConnectionState.CONNECTED) {
            add(QuickStat(EvIcons.Refresh, EvBlue, "Last Sync", "Just now", "", "Updated", onVariant))
        }
    }

    if (stats.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dim.lg, horizontal = Dim.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            stats.forEachIndexed { i, stat ->
                QuickStatCell(stat, Modifier.weight(1f))
                if (i < stats.lastIndex) CellDivider()
            }
        }
    }
}

@Composable
private fun QuickStatCell(stat: QuickStat, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Dim.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xxs)
    ) {
        androidx.compose.material3.Icon(
            imageVector = stat.icon,
            contentDescription = null,
            tint = stat.tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (stat.unit.isNotEmpty()) {
                Text(
                    text = " ${stat.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = stat.caption,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = stat.captionColor,
            maxLines = 1
        )
    }
}

@Composable
private fun CellDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 40.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    )
}

/** Taller, softer divider sized for the stacked Today's-Summary stat cells. */
@Composable
private fun SummaryDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 56.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    )
}

/* -------------------------------------------------------------------------- */
/*  Vehicle health                                                            */
/* -------------------------------------------------------------------------- */

@Composable
private fun VehicleHealthSection(state: DashboardUiState) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    val allNormal = !state.faultActive
    Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Vehicle Health",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (allNormal) "All Systems Normal" else "Attention Needed",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (allNormal) EvGreen else EvAmber
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
            HealthCard(
                icon = EvIcons.Shield,
                tint = if (state.faultActive) EvRed else EvGreen,
                title = "Controller",
                status = if (state.faultActive) "Fault" else "OK",
                statusColor = if (state.faultActive) EvRed else EvGreen,
                modifier = Modifier.weight(1f)
            )
            HealthCard(
                icon = if (connected) EvIcons.Bluetooth else EvIcons.BluetoothOff,
                tint = EvBlue,
                title = "Bluetooth",
                status = if (connected) "Connected" else "Offline",
                statusColor = if (connected) EvBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            HealthCard(
                icon = EvIcons.Cpu,
                tint = EvViolet,
                title = "System",
                status = if (connected) "Normal" else "Idle",
                statusColor = EvViolet,
                modifier = Modifier.weight(1f)
            )
            HealthCard(
                icon = EvIcons.Pulse,
                tint = if (state.faultActive) EvRed else EvGreen,
                title = "Error",
                status = if (state.faultActive) "#${state.faultCode}" else "None",
                statusColor = if (state.faultActive) EvRed else EvGreen,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HealthCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(Dim.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dim.md, horizontal = Dim.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            IconBubble(icon = icon, tint = tint)
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Today's summary — omitted entirely when there's no session data.          */
/* -------------------------------------------------------------------------- */

@Composable
private fun TodaySummaryCard(state: DashboardUiState, onOpenHistory: () -> Unit) {
    val app = LocalAppSettings.current
    val distanceKm = state.tripStats.distanceKm
    val durationSec = state.tripStats.durationSec
    val whPerKm = state.efficiency.whPerKm

    val hasData = distanceKm > 0f || durationSec > 0L || whPerKm != null
    if (!hasData) return

    val distDisplay = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH)
        distanceKm * 0.621371f else distanceKm

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Today's Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.clickable(onClick = onOpenHistory),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dim.xxs)
                ) {
                    Text(
                        text = "View History",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    androidx.compose.material3.Icon(
                        imageVector = EvIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryStat(
                    icon = EvIcons.Road,
                    tint = EvBlue,
                    value = "%.1f".format(distDisplay),
                    unit = app.speedUnit.distanceSuffix,
                    label = "Distance",
                    modifier = Modifier.weight(1f)
                )
                SummaryDivider()
                SummaryStat(
                    icon = EvIcons.Timer,
                    tint = EvViolet,
                    value = "${durationSec / 60}",
                    unit = "min",
                    label = "Duration",
                    modifier = Modifier.weight(1f)
                )
                if (whPerKm != null) {
                    SummaryDivider()
                    SummaryStat(
                        icon = EvIcons.Bolt,
                        tint = EvGreen,
                        value = EnergyFormatter.formatEfficiency(whPerKm),
                        unit = "Wh/km",
                        label = "Efficiency",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(
    icon: ImageVector,
    tint: Color,
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier
) {
    // Vertical cell: icon bubble on top, value + unit on one line, label below.
    // Stacking vertically (rather than icon-beside-text) keeps every column wide
    // enough that "Wh/km" and the label never wrap or clip in a ~1/3-width slot.
    Column(
        modifier = modifier.padding(horizontal = Dim.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        IconBubble(icon = icon, tint = tint)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = " $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Small shared pieces                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Dim.md),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    )
}

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else -> "Good Night"
    }
}
