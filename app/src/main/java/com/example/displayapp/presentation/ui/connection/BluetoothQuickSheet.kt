package com.example.displayapp.presentation.ui.connection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.data.bluetooth.controller.AdapterState
import com.example.displayapp.presentation.state.BluetoothUiState
import com.example.displayapp.presentation.state.UiDeviceState
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.viewmodel.BluetoothViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvBlue
import com.example.displayapp.ui.theme.EvBlueBright
import com.example.displayapp.ui.theme.EvRed
import kotlinx.coroutines.launch

/**
 * Tesla/Rivian-style quick-settings sheet for Bluetooth devices.
 *
 * Thin wrapper around [BluetoothManagementSections] — same body the
 * DeviceScanScreen renders, just hosted in a ModalBottomSheet here so it
 * floats over Drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothQuickSheet(
    viewModel: BluetoothViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    fun dismiss() {
        viewModel.stopScan()
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BluetoothSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dim.lg)
                .padding(bottom = Dim.lg),
            verticalArrangement = Arrangement.spacedBy(Dim.md)
        ) {
            BluetoothManagementSections(
                viewModel = viewModel,
                showAutoConnectToggle = false
            )
            Spacer(Modifier.height(Dim.sm))
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Shared body — used by BluetoothQuickSheet (Drive) and DeviceScanScreen.    */
/* -------------------------------------------------------------------------- */

/**
 * Renders the full Bluetooth management UI:
 *  - Adapter on/off toggle header,
 *  - Inline error chip (animated),
 *  - Scan row with animated indicator,
 *  - Sectioned device list (Connected / Previously connected / Paired / Available),
 *  - Empty state with a "Scan now" CTA.
 *
 * Permission flow + enable intent are owned here so both call sites get the
 * same behavior. The caller wraps in whatever layout it needs (Column inside
 * a ModalBottomSheet for the sheet, Column inside a Scaffold for the page).
 *
 * Does not add scroll, screen gutters, or insets — caller controls those.
 *
 * @param showAutoConnectToggle when true and a saved device exists, renders
 *   an "Auto-connect on launch" row beneath the Previously Connected device.
 *   The sheet keeps this off; the scan screen turns it on so the toggle has
 *   a permanent home.
 */
@Composable
fun BluetoothManagementSections(
    viewModel: BluetoothViewModel,
    showAutoConnectToggle: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val enableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onEnableResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    // BluetoothAdapter.startDiscovery() silently returns false without these.
    val scanPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScan()
        } else {
            viewModel.surfaceError("Allow Bluetooth permissions to scan")
        }
    }
    val requestScan: () -> Unit = {
        val allGranted = scanPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.startScan()
        else permissionLauncher.launch(scanPermissions)
    }

    AdapterToggleHeader(
        adapterState = state.adapterState,
        statusLine = state.statusLine,
        onToggle = { wantOn ->
            // Android 13+ no longer lets apps disable() the adapter — route to settings.
            if (wantOn) enableLauncher.launch(viewModel.enableIntent())
            else enableLauncher.launch(viewModel.disableIntent())
        }
    )

    AnimatedVisibility(
        visible = state.errorMessage != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        state.errorMessage?.let { ErrorChip(message = it, onDismiss = viewModel::clearError) }
    }

    if (state.adapterState == AdapterState.ON) {
        ScanRow(
            isScanning = state.isScanning,
            onStartScan = requestScan,
            onStopScan = { viewModel.stopScan() }
        )

        state.connected?.let { device ->
            Section(title = "Connected") {
                BluetoothDeviceRow(
                    device = device,
                    secondaryLabel = if (device.state == UiDeviceState.CONNECTED) "Tap to disconnect" else null,
                    showForget = true,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.disconnect()
                    },
                    onDisconnect = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.disconnect()
                    },
                    onForget = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.forget(device.address)
                    }
                )
            }
        }

        state.previouslyConnected?.let { device ->
            Section(title = "Previously connected") {
                BluetoothDeviceRow(
                    device = device,
                    secondaryLabel = "Tap to reconnect",
                    showForget = true,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.connect(device.address, device.name)
                    },
                    onForget = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.forget(device.address)
                    }
                )
                if (showAutoConnectToggle) {
                    AutoConnectRow(
                        enabled = state.autoConnect,
                        onChanged = viewModel::setAutoConnect
                    )
                }
            }
        }

        if (state.paired.isNotEmpty()) {
            Section(title = "Paired devices") {
                Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
                    state.paired.forEach { device ->
                        BluetoothDeviceRow(
                            device = device,
                            showForget = true,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.connect(device.address, device.name)
                            },
                            onForget = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.forget(device.address)
                            }
                        )
                    }
                }
            }
        }

        if (state.available.isNotEmpty()) {
            Section(title = "Available") {
                Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
                    state.available.forEach { device ->
                        BluetoothDeviceRow(
                            device = device,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.connect(device.address, device.name)
                            }
                        )
                    }
                }
            }
        }

        if (state.showEmptyState) {
            EmptyDiscoveryHint(onScan = requestScan)
        }
    } else if (state.adapterState != AdapterState.UNSUPPORTED) {
        // Adapter is off / transitioning — show a friendly prompt instead of leaving
        // the page empty. The user enables BT via the toggle in the header above.
        AdapterOffHint(adapterState = state.adapterState)
    }
}

/* -------------------------------------------------------------------------- */
/*  Top toggle header                                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun AdapterToggleHeader(
    adapterState: AdapterState,
    statusLine: String,
    onToggle: (Boolean) -> Unit
) {
    val isOn = adapterState == AdapterState.ON
    val accent = if (isOn) EvBlue else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isOn) Brush.horizontalGradient(
                        listOf(
                            EvBlue.copy(alpha = 0.10f),
                            EvBlueBright.copy(alpha = 0.04f)
                        )
                    ) else Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dim.lg, vertical = Dim.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isOn) EvIcons.Bluetooth else EvIcons.BluetoothOff,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(Dim.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bluetooth",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusLine,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (adapterState != AdapterState.UNSUPPORTED) {
                    Switch(
                        checked = isOn,
                        onCheckedChange = onToggle,
                        enabled = !adapterState.isTransitioning,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = EvBlue,
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Scan row                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun ScanRow(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Dim.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dim.lg, vertical = Dim.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScanIndicator(isScanning = isScanning)
            Spacer(Modifier.width(Dim.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isScanning) "Scanning..." else "Scan for devices",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isScanning) "Looking for nearby Bluetooth devices"
                           else "Tap to discover nearby Bluetooth devices",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { if (isScanning) onStopScan() else onStartScan() }) {
                Text(
                    text = if (isScanning) "Stop" else "Scan",
                    color = EvBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ScanIndicator(isScanning: Boolean) {
    if (isScanning) {
        val transition = rememberInfiniteTransition(label = "scan-anim")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scan-rot"
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(EvBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .rotate(rotation)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                EvBlue.copy(alpha = 0f),
                                EvBlue.copy(alpha = 0.0f),
                                EvBlue.copy(alpha = 0.6f),
                                EvBlue
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = EvIcons.Bluetooth,
                    contentDescription = null,
                    tint = EvBlue,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = EvIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Section + supporting pieces                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dim.xs, top = Dim.sm)
        )
        content()
    }
}

@Composable
private fun AutoConnectRow(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dim.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dim.lg, vertical = Dim.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-connect on launch",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Reconnect to this device automatically",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChanged,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = EvBlue,
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
    }
}

@Composable
private fun ErrorChip(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dim.md),
        color = EvRed.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, EvRed.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dim.lg, vertical = Dim.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = EvRed,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = EvRed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryHint(onScan: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dim.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(EvBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = EvIcons.Bluetooth,
                    contentDescription = null,
                    tint = EvBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "No devices yet",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Pair your EV controller in Bluetooth settings, or tap Scan to look for nearby devices.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onScan) {
                Text("Scan now", color = EvBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AdapterOffHint(adapterState: AdapterState) {
    val (title, body) = when (adapterState) {
        AdapterState.TURNING_ON -> "Turning on..." to "Bluetooth is starting up."
        AdapterState.TURNING_OFF -> "Turning off..." to "Bluetooth is shutting down."
        else -> "Bluetooth is off" to "Turn on Bluetooth to scan for nearby EV controllers."
    }
    Surface(
        shape = RoundedCornerShape(Dim.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dim.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dim.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = EvIcons.BluetoothOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BluetoothSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dim.sm),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}
