package com.example.displayapp.presentation.ui.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Requests Bluetooth (and, pre-Android 12, location) permissions once on launch
 * and then **always renders [content]** — it is intentionally non-blocking.
 *
 * The app opens straight to the Home page; the runtime permission dialog appears
 * over it on first launch. If the user defers or denies, Home still shows and the
 * Bluetooth surfaces (pairing sheet, scan) re-request the exact permissions they
 * need at the point of use ([com.example.displayapp.presentation.ui.connection.BluetoothManagementSections]),
 * so nothing is permanently gated behind an all-or-nothing wall.
 */
@Composable
fun PermissionHandler(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily by the BT surfaces that need it */ }

    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) permissionLauncher.launch(requiredPermissions)
    }

    content()
}
