package com.innodrive.evdash.presentation.ui.permissions

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
 * Requests Bluetooth + notification (and, pre-Android 12, location) permissions once
 * on launch and then **always renders [content]** — it is intentionally non-blocking.
 *
 * The app opens straight to the Home page; the runtime permission dialog appears
 * over it on first launch. If the user defers or denies, Home still shows and the
 * Bluetooth surfaces (pairing sheet, scan) re-request the exact permissions they
 * need at the point of use ([com.innodrive.evdash.presentation.ui.connection.BluetoothManagementSections]),
 * so nothing is permanently gated behind an all-or-nothing wall.
 *
 * **Location is deliberately NOT here** — it's requested at the point of use when the
 * Drive page opens (`LocationPermissionEffect`), since that's where the map/nav needs
 * a precise fix. `POST_NOTIFICATIONS` (Android 13+) is the runtime grant that lets the
 * app *post* notifications (the foreground-service banner); it is separate from the
 * "Notification access" special grant the mirroring listener needs, which is a Settings
 * toggle, not a runtime dialog.
 */
@Composable
fun PermissionHandler(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // Android 13+ gates posting notifications behind a runtime grant — ask up
            // front so the foreground-service notification shows from first launch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
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
