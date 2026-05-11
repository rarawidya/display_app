package com.example.displayapp.presentation.ui.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

@Composable
fun PermissionHandler(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    var shouldShowRationale by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (!permissionsGranted) {
            shouldShowRationale = true
        }
    }

    LaunchedEffect(Unit) {
        permissionsGranted = checkPermissions()
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    if (permissionsGranted) {
        content()
    } else {
        PermissionDeniedContent(
            showRationale = shouldShowRationale,
            onRequestPermissions = {
                permissionLauncher.launch(requiredPermissions)
            }
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    showRationale: Boolean,
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Bluetooth Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (showRationale) {
                "This app needs Bluetooth permissions to communicate with your EV controller. Please grant the permissions to continue."
            } else {
                "Requesting Bluetooth permissions..."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showRationale) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}
