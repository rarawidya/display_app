package com.example.displayapp.presentation.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.data.bluetooth.controller.AdapterState
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.state.BluetoothUiState
import com.example.displayapp.presentation.state.UiDeviceState
import com.example.displayapp.presentation.ui.connection.BluetoothManagementSections
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.BluetoothViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvBlueDeep
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed

/**
 * Device picker / connection home screen.
 *
 * Layout (top → bottom):
 *  1. HeroStatusCard — animated connection state badge + Open Dashboard / Disconnect quick actions.
 *  2. [BluetoothManagementSections] — adapter on/off toggle, scan row, sectioned device list
 *     (Connected / Previously connected + Auto-connect / Paired / Available). Same component
 *     the Drive page's long-press sheet renders.
 *  3. SimulatorCta — "Try with simulator" outline button (skips Bluetooth entirely).
 *
 * Permission, enable/disable, and connect/disconnect flows are owned by
 * [BluetoothManagementSections], so this screen stays focused on the framing
 * (top app bar, hero, simulator escape hatch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(
    viewModel: BluetoothViewModel,
    onNavigateToDashboard: () -> Unit = {},
    onUseSimulator: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Devices",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                EvIcons.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dim.screenGutter)
                .padding(bottom = Dim.xxl),
            verticalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            HeroStatusCard(
                state = state,
                onNavigateToDashboard = onNavigateToDashboard,
                onDisconnect = viewModel::disconnect
            )

            BluetoothManagementSections(
                viewModel = viewModel,
                showAutoConnectToggle = true
            )

            Spacer(Modifier.height(Dim.sm))
            SimulatorCta(onClick = onUseSimulator)
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Hero status card                                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun HeroStatusCard(
    state: BluetoothUiState,
    onNavigateToDashboard: () -> Unit,
    onDisconnect: () -> Unit
) {
    val connState = state.connected?.state.toConnectionState(state.isScanning, state.adapterState)
    val theme = visualThemeFor(connState, state.isScanning, state.adapterState)
    val animatedAccent by animateColorAsState(targetValue = theme.accent, label = "hero-accent")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            theme.accent.copy(alpha = 0.18f),
                            theme.accent.copy(alpha = 0.02f)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StateBadge(
                        accent = animatedAccent,
                        state = connState,
                        isScanning = state.isScanning,
                        adapterState = state.adapterState
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = theme.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (theme.subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = theme.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = connState == ConnectionState.CONNECTED,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
                        PrimaryAction(
                            label = "Open dashboard",
                            onClick = onNavigateToDashboard,
                            fill = EvBlue
                        )
                        SecondaryAction(
                            label = "Disconnect",
                            onClick = onDisconnect,
                            stroke = EvRed.copy(alpha = 0.55f),
                            contentColor = EvRed
                        )
                    }
                }
            }
        }
    }
}

private data class StatusTheme(val title: String, val subtitle: String?, val accent: Color)

@Composable
private fun visualThemeFor(
    connection: ConnectionState,
    isScanning: Boolean,
    adapterState: AdapterState
): StatusTheme {
    if (adapterState != AdapterState.ON && adapterState != AdapterState.UNSUPPORTED) {
        return StatusTheme("Bluetooth is off", "Turn on Bluetooth to pair", EvBlueDeep)
    }
    if (adapterState == AdapterState.UNSUPPORTED) {
        return StatusTheme("Bluetooth not available", "This device has no Bluetooth adapter", EvBlueDeep)
    }
    return when (connection) {
        ConnectionState.CONNECTED -> StatusTheme("Connected", "Telemetry is live", EvGreen)
        ConnectionState.CONNECTING -> StatusTheme("Connecting…", "Establishing the link", EvAmber)
        ConnectionState.RECONNECTING -> StatusTheme("Reconnecting…", "Lost signal · retrying", EvAmber)
        ConnectionState.SCANNING -> StatusTheme("Searching for vehicles…", "Listening for Bluetooth", EvBlue)
        ConnectionState.DISCONNECTED ->
            if (isScanning) StatusTheme("Searching…", "Listening for Bluetooth", EvBlue)
            else StatusTheme("Pair your vehicle", "Tap Scan below to discover nearby devices", EvBlueDeep)
    }
}

@Composable
private fun StateBadge(
    accent: Color,
    state: ConnectionState,
    isScanning: Boolean,
    adapterState: AdapterState
) {
    val showPulse = adapterState == AdapterState.ON && (
        isScanning ||
        state == ConnectionState.CONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.SCANNING
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        if (showPulse) {
            PulseRing(color = accent)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            val icon = when {
                adapterState != AdapterState.ON -> EvIcons.BluetoothOff
                state == ConnectionState.CONNECTED -> EvIcons.Bluetooth
                isScanning -> EvIcons.Bluetooth
                state == ConnectionState.DISCONNECTED -> EvIcons.BluetoothOff
                else -> EvIcons.Bluetooth
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PulseRing(color: Color) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    @Composable
    fun ring(delayMs: Int) {
        val scale by infinite.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_500, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse-scale-$delayMs"
        )
        val alpha by infinite.animateFloat(
            initialValue = 0.55f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_500, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse-alpha-$delayMs"
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.35f))
        )
    }
    ring(0)
    ring(750)
}

/* -------------------------------------------------------------------------- */
/*  Simulator CTA + button primitives                                         */
/* -------------------------------------------------------------------------- */

@Composable
private fun SimulatorCta(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Try with simulator",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, fill: Color) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = fill
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    stroke: Color,
    contentColor: Color
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, stroke)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  UiDeviceState → ConnectionState mapping for the hero card                  */
/* -------------------------------------------------------------------------- */

private fun UiDeviceState?.toConnectionState(
    isScanning: Boolean,
    adapterState: AdapterState
): ConnectionState = when {
    this == UiDeviceState.CONNECTED -> ConnectionState.CONNECTED
    this == UiDeviceState.CONNECTING -> ConnectionState.CONNECTING
    this == UiDeviceState.RECONNECTING -> ConnectionState.RECONNECTING
    isScanning && adapterState == AdapterState.ON -> ConnectionState.SCANNING
    else -> ConnectionState.DISCONNECTED
}
