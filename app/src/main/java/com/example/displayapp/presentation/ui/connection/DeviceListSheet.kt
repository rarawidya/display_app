package com.example.displayapp.presentation.ui.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListSheet(
    devices: List<BluetoothDeviceInfo>,
    connectionState: ConnectionState,
    onDeviceSelected: (String) -> Unit,
    onScanClick: () -> Unit,
    onDisconnect: () -> Unit,
    onUseSimulator: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Bluetooth Devices",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (connectionState == ConnectionState.CONNECTED) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onScanClick,
                        enabled = connectionState != ConnectionState.SCANNING,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (connectionState == ConnectionState.SCANNING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Scan")
                        }
                    }
                }

                OutlinedButton(
                    onClick = onUseSimulator,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Simulator")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            if (devices.isEmpty()) {
                Text(
                    text = "No devices found. Tap Scan to search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(300.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(
                            device = device,
                            onClick = { onDeviceSelected(device.address) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceItem(
    device: BluetoothDeviceInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
    }
}
