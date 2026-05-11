package com.example.displayapp.data.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Snapshot of runtime-permission state for a single permission.
 *
 * Driven by the Settings screen — recomputed on every lifecycle RESUMED so
 * that grants made in the system settings page reflect when the user returns.
 */
enum class PermissionState { GRANTED, DENIED, NOT_APPLICABLE }

data class PermissionItem(
    val key: AppPermission,
    val state: PermissionState
)

/**
 * Single source of truth for which runtime permissions the EV cockpit cares about,
 * and what they're called in the UI.
 */
enum class AppPermission(
    val androidName: String,
    val displayName: String,
    val rationale: String,
    /** lowest SDK that exposes this permission as a runtime grant */
    val minSdk: Int = Build.VERSION_CODES.M
) {
    BLUETOOTH_SCAN(
        Manifest.permission.BLUETOOTH_SCAN,
        "Bluetooth Scan",
        "Required to find your vehicle's Bluetooth controller.",
        minSdk = Build.VERSION_CODES.S
    ),
    BLUETOOTH_CONNECT(
        Manifest.permission.BLUETOOTH_CONNECT,
        "Bluetooth Connect",
        "Required to pair and exchange telemetry with your vehicle.",
        minSdk = Build.VERSION_CODES.S
    ),
    LOCATION(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "Location",
        "Used by Bluetooth scan and the live map."
    ),
    NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        "Notifications",
        "Required for the foreground service that keeps telemetry running.",
        minSdk = Build.VERSION_CODES.TIRAMISU
    )
}

class PermissionStatusProvider(private val context: Context) {

    fun snapshot(): List<PermissionItem> = AppPermission.entries.map { p ->
        val state = when {
            Build.VERSION.SDK_INT < p.minSdk -> PermissionState.NOT_APPLICABLE
            ContextCompat.checkSelfPermission(context, p.androidName) ==
                PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
            else -> PermissionState.DENIED
        }
        PermissionItem(p, state)
    }

    /** Build an intent that opens this app's system-Settings page. */
    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
