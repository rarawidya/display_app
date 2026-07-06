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
import androidx.compose.foundation.layout.IntrinsicSize
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(Dim.md)
                ) {
                    BatteryCard(
                        state = state,
                        connected = connected,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    TodayRideCard(
                        state = state,
                        onOpenHistory = onOpenHistory,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                VehicleInfoCard(state = state)
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
/*  Battery + Today's ride — paired summary cards (see home_battery.png).      */
/* -------------------------------------------------------------------------- */

@Composable
private fun BatteryCard(
    state: DashboardUiState,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val app = LocalAppSettings.current
    val known = connected && state.batteryKnown
    val pct = state.batteryPercent.coerceIn(0, 100)
    val fillColor = when {
        !known -> MaterialTheme.colorScheme.outline
        pct > 50 -> EvGreen
        pct > 20 -> EvAmber
        else -> EvRed
    }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    // Estimated range = remaining pack energy ÷ rolling Wh/km (EfficiencyTracker.
    // computeRangeKm). Null until there's valid consumption data, so we show "—"
    // rather than a fabricated number. Displayed in the user's distance unit.
    val rangeKm = state.efficiency.rangeKm
    val rangeValue = if (known && rangeKm != null) {
        val v = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH)
            rangeKm * 0.621371f else rangeKm
        "%.0f".format(v)
    } else "—"

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Text(
                text = "Battery",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (known) "$pct" else "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (known) {
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                // Battery glyph sits beside the value, tinted to the charge state.
                androidx.compose.material3.Icon(
                    imageVector = EvIcons.Battery,
                    contentDescription = null,
                    tint = if (known) fillColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(trackColor)
            ) {
                if (known) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct / 100f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(fillColor)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(Dim.xxs)) {
                Text(
                    text = "Estimated Range",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = rangeValue,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = " ${app.speedUnit.distanceSuffix}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayRideCard(
    state: DashboardUiState,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = LocalAppSettings.current
    val distanceKm = state.tripStats.distanceKm
    val durationSec = state.tripStats.durationSec
    val distDisplay = if (app.speedUnit == com.example.displayapp.domain.model.SpeedUnit.MPH)
        distanceKm * 0.621371f else distanceKm

    Surface(
        shape = RoundedCornerShape(Dim.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Today's Ride",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Icon(
                    imageVector = EvIcons.ChevronRight,
                    contentDescription = "View history",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenHistory)
                )
            }
            Spacer(Modifier.weight(1f))
            RideStat(
                icon = EvIcons.Road,
                tint = EvBlue,
                value = "%.1f".format(distDisplay),
                unit = app.speedUnit.distanceSuffix,
                label = "Distance"
            )
            RideStat(
                icon = EvIcons.Timer,
                tint = EvViolet,
                value = "${durationSec / 60}",
                unit = "min",
                label = "Duration"
            )
        }
    }
}

@Composable
private fun RideStat(
    icon: ImageVector,
    tint: Color,
    value: String,
    unit: String,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dim.xxs)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Vehicle information — spec + live-status sheet (à la NIU / Ather app).     */
/* -------------------------------------------------------------------------- */

@Composable
private fun VehicleInfoCard(state: DashboardUiState) {
    val app = LocalAppSettings.current
    val connected = state.connectionState == ConnectionState.CONNECTED
    val dash = "—"

    // A calm at-a-glance list (summary style, not live gauges). Only fields that
    // appear nowhere else on Home live here — no value is shown twice.
    fun temp(c: Int) = "%.0f %s".format(
        app.temperatureUnit.convertFromCelsius(c.toFloat()), app.temperatureUnit.suffix
    )
    val modeValue = if (connected) modeLabel(state.vehicleMode) else dash
    val motorTempValue = if (connected && state.temperature > 0) temp(state.temperature) else dash
    val controllerTempValue = if (connected && state.controllerTemperature > 0)
        temp(state.controllerTemperature) else dash

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        // Section title sits above the card.
        Text(
            text = "Vehicle Information",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(Dim.cardCorner),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Stacked sideways: the key channels sit in a row, split by separators.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dim.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VehicleStat(EvIcons.Drive, EvViolet, modeValue, "Riding Mode", Modifier.weight(1f))
                VDivider()
                VehicleStat(EvIcons.Thermo, EvAmber, motorTempValue, "Motor Temp", Modifier.weight(1f))
                VDivider()
                VehicleStat(EvIcons.Cpu, EvBlue, controllerTempValue, "Controller Temp", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VehicleStat(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = Dim.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xs)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
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
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** Vertical separator between the stacked-sideways vehicle stats. */
@Composable
private fun VDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 52.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    )
}

private fun modeLabel(mode: com.example.displayapp.domain.model.VehicleMode) = when (mode) {
    com.example.displayapp.domain.model.VehicleMode.PARK -> "Parked"
    com.example.displayapp.domain.model.VehicleMode.ECO -> "Eco"
    com.example.displayapp.domain.model.VehicleMode.NORMAL -> "Normal"
    com.example.displayapp.domain.model.VehicleMode.SPORT -> "Sport"
    com.example.displayapp.domain.model.VehicleMode.REGEN -> "Regen"
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
