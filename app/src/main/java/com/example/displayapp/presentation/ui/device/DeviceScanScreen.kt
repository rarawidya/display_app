package com.example.displayapp.presentation.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.presentation.viewmodel.DeviceScreenState
import com.example.displayapp.presentation.viewmodel.DeviceViewModel

@Composable
fun DeviceScanScreen(
    viewModel: DeviceViewModel,
    onNavigateToDashboard: () -> Unit = {},
    onUseSimulator: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DeviceScanContent(
        state = state,
        onScanClick = { viewModel.startScan() },
        onStopScan = { viewModel.stopScan() },
        onDeviceClick = { device ->
            viewModel.connectViaService(context, device.address, device.name)
        },
        onDisconnect = { viewModel.disconnectViaService(context) },
        onAutoConnectChanged = { viewModel.setAutoConnect(it) },
        onForgetDevice = { viewModel.forgetDevice() },
        onNavigateToDashboard = onNavigateToDashboard,
        onUseSimulator = onUseSimulator
    )
}

@Composable
fun DeviceScanContent(
    state: DeviceScreenState,
    onScanClick: () -> Unit = {},
    onStopScan: () -> Unit = {},
    onDeviceClick: (BluetoothDeviceInfo) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onAutoConnectChanged: (Boolean) -> Unit = {},
    onForgetDevice: () -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    onUseSimulator: () -> Unit = {}
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Bluetooth Devices",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection status
            ConnectionCard(
                state = state.connectionState,
                onDisconnect = onDisconnect,
                onNavigateToDashboard = onNavigateToDashboard
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Saved device card
            state.savedDevice?.let { saved ->
                SavedDeviceCard(
                    name = saved.name,
                    address = saved.address,
                    autoConnect = saved.autoConnect,
                    onAutoConnectChanged = onAutoConnectChanged,
                    onForget = onForgetDevice
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Scan controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Available Devices",
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.isScanning) {
                    OutlinedButton(onClick = onStopScan) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onScanClick) {
                        Text("Scan")
                    }
                }
            }

            // Scanning indicator
            AnimatedVisibility(visible = state.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Device list
            if (state.devices.isEmpty() && !state.isScanning) {
                Text(
                    text = "No devices found. Tap Scan to discover nearby devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.devices, key = { it.address }) { device ->
                        DeviceListItem(
                            device = device,
                            isConnecting = state.connectionState == ConnectionState.CONNECTING,
                            onClick = { onDeviceClick(device) }
                        )
                    }
                }
            }

            // Try-without-hardware affordance — pinned at the bottom of the column
            OutlinedButton(
                onClick = onUseSimulator,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Try with simulator")
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    onDisconnect: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val (color, label) = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF757575) to "Not Connected"
        ConnectionState.SCANNING -> Color(0xFFFFAB00) to "Scanning..."
        ConnectionState.CONNECTING -> Color(0xFFFFAB00) to "Connecting..."
        ConnectionState.CONNECTED -> Color(0xFF00E676) to "Connected"
        ConnectionState.RECONNECTING -> Color(0xFFFFAB00) to "Reconnecting..."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = color
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = label,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }

            if (state == ConnectionState.CONNECTED) {
                Row {
                    TextButton(onClick = onNavigateToDashboard) {
                        Text("Dashboard")
                    }
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect", color = Color(0xFFFF1744))
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceCard(
    name: String,
    address: String,
    autoConnect: Boolean,
    onAutoConnectChanged: (Boolean) -> Unit,
    onForget: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Last Device",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = name, fontWeight = FontWeight.Medium)
                    Text(
                        text = address,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onForget) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Auto-connect on startup", fontSize = 14.sp)
                Switch(
                    checked = autoConnect,
                    onCheckedChange = onAutoConnectChanged
                )
            }
        }
    }
}

@Composable
private fun DeviceListItem(
    device: BluetoothDeviceInfo,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Connect",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
