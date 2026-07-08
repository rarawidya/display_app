package com.example.displayapp.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.data.permissions.AppPermission
import com.example.displayapp.data.permissions.PermissionState
import com.example.displayapp.data.permissions.PermissionStatusProvider
import com.example.displayapp.data.persistence.StorageInfo
import com.example.displayapp.data.preferences.SavedDevice
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.ThemeMode
import com.example.displayapp.presentation.state.SettingsUiState
import com.example.displayapp.service.NotificationRelayService
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.settings.components.ActionRow
import com.example.displayapp.presentation.ui.settings.components.ChoiceRow
import com.example.displayapp.presentation.ui.settings.components.ConfirmDialog
import com.example.displayapp.presentation.ui.settings.components.PermissionRow
import com.example.displayapp.presentation.ui.settings.components.PreferenceRow
import com.example.displayapp.presentation.ui.settings.components.SectionDivider
import com.example.displayapp.presentation.ui.settings.components.SettingsSection
import com.example.displayapp.presentation.ui.settings.components.SwitchRow
import com.example.displayapp.presentation.ui.settings.components.ValueRow
import com.example.displayapp.presentation.viewmodel.SettingsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvGreen
import com.example.displayapp.ui.theme.EvRed
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    permissionProvider: PermissionStatusProvider,
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Whether the user has granted this app "Notification access" in system
    // settings — a system-bound grant with no runtime-permission Flow, so we
    // re-read it on every RESUME (the user may toggle it and return).
    var notificationAccess by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    // Doze can kill the bound notification listener (NOTIFICATION-APP-FIXME.md §1);
    // surface a one-tap exemption row until the app is exempt.
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                viewModel.refreshStorage()
                notificationAccess =
                    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                batteryExempt = isIgnoringBatteryOptimizations(context)
                // Just came back from the access screen: if it's now granted, nudge
                // the system to bind the listener (it won't always on its own after
                // an install/update) so relaying starts without a reboot.
                if (notificationAccess) {
                    NotificationRelayService.requestRebindIfGranted(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Call mirroring needs READ_PHONE_STATE (calls don't reach the notification
    // listener) and ANSWER_PHONE_CALLS (the cluster's Answer/End buttons — same
    // "Phone" permission group, so it's one dialog). Requested at point of use —
    // when the relay toggle flips on — and the toggle is enabled either way: a
    // denial just means notifications-only.
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.setNotificationRelayEnabled(true) }

    SettingsContent(
        state = state,
        notificationAccessGranted = notificationAccess,
        onBack = onBack,
        onThemeMode = viewModel::setThemeMode,
        onAutoConnect = viewModel::setAutoConnect,
        onForgetDevice = viewModel::forgetDevice,
        onSimulatorMode = viewModel::setSimulatorMode,
        onNotificationRelay = { enabled ->
            val phonePermissions = buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
            }
            val missing = enabled && phonePermissions.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing) {
                phonePermissionLauncher.launch(phonePermissions.toTypedArray())
            } else {
                viewModel.setNotificationRelayEnabled(enabled)
            }
        },
        onOpenNotificationAccess = {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        batteryExempt = batteryExempt,
        onRequestBatteryExemption = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onClearTrips = viewModel::clearTripHistory,
        onUnlockDeveloper = viewModel::unlockDeveloperMode,
        onOpenDeveloper = onOpenDeveloper,
        onManagePermissions = {
            context.startActivity(permissionProvider.appDetailsIntent())
        }
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    notificationAccessGranted: Boolean,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAutoConnect: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
    onSimulatorMode: (Boolean) -> Unit,
    onNotificationRelay: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    batteryExempt: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onClearTrips: () -> Unit,
    onUnlockDeveloper: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onManagePermissions: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            EvIcons.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dim.screenGutter)
                    .padding(bottom = Dim.xxl),
                verticalArrangement = Arrangement.spacedBy(Dim.lg)
            ) {
                AppearanceSection(theme = state.theme, onThemeMode = onThemeMode)
                ConnectionSection(
                    connection = state.connectionState,
                    savedDevice = state.savedDevice,
                    app = state.app,
                    onAutoConnect = onAutoConnect,
                    onForgetDevice = onForgetDevice,
                    onSimulatorMode = onSimulatorMode
                )
                NotificationsSection(
                    app = state.app,
                    accessGranted = notificationAccessGranted,
                    onToggle = onNotificationRelay,
                    onOpenAccess = onOpenNotificationAccess,
                    batteryExempt = batteryExempt,
                    onRequestBatteryExemption = onRequestBatteryExemption
                )
                StorageSection(
                    storage = state.storage,
                    onClearTrips = onClearTrips
                )
                PermissionsSection(state = state, onManage = onManagePermissions)
                AboutSection(
                    state = state,
                    devUnlocked = state.app.devModeUnlocked,
                    onUnlock = {
                        onUnlockDeveloper()
                        scope.launch { snackbar.showSnackbar("Developer mode unlocked") }
                    },
                    onOpenDeveloper = onOpenDeveloper
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Dim.lg)
            ) { data -> Snackbar(snackbarData = data) }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  1. Appearance                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun AppearanceSection(
    theme: com.example.displayapp.domain.model.ThemeSettings,
    onThemeMode: (ThemeMode) -> Unit
) {
    SettingsSection(title = "Appearance") {
        ChoiceRow(
            title = "Theme",
            options = ThemeMode.entries,
            selected = theme.mode,
            onSelected = onThemeMode,
            labelFor = { themeLabel(it) },
            descriptionFor = { themeDescription(it) }
        )
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.LIGHT  -> "Light"
    ThemeMode.DARK   -> "Dark"
    ThemeMode.SYSTEM -> "Follow system"
}
private fun themeDescription(mode: ThemeMode): String? = when (mode) {
    ThemeMode.LIGHT  -> "Bright cockpit · daytime"
    ThemeMode.DARK   -> "Carbon cockpit · night driving"
    ThemeMode.SYSTEM -> "Switches with your phone's mode"
}

/* -------------------------------------------------------------------------- */
/*  2. Connection                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun ConnectionSection(
    connection: ConnectionState,
    savedDevice: SavedDevice?,
    app: AppSettings,
    onAutoConnect: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
    onSimulatorMode: (Boolean) -> Unit
) {
    SettingsSection(title = "Connection") {
        val (statusColor, statusLabel) = when (connection) {
            ConnectionState.CONNECTED    -> EvGreen to "Connected"
            ConnectionState.DISCONNECTED -> EvRed   to "Disconnected"
            else                          -> EvAmber to connection.name.lowercase()
                .replaceFirstChar { it.uppercase() }
        }
        ValueRow(
            title = "Status",
            value = statusLabel,
            leadingIcon = EvIcons.Bluetooth,
            valueColor = statusColor
        )
        SectionDivider()

        if (savedDevice != null) {
            PreferenceRow(
                title = savedDevice.name,
                subtitle = savedDevice.address,
                leadingIcon = EvIcons.Bluetooth
            )
            SectionDivider()
            ActionRow(
                title = "Forget device",
                subtitle = "Clears paired Bluetooth metadata",
                leadingTint = EvRed,
                onClick = onForgetDevice
            )
            SectionDivider()
        } else {
            ValueRow(
                title = "Paired device",
                value = "—",
                subtitle = "No device saved · pair from the Drive screen"
            )
            SectionDivider()
        }

        SwitchRow(
            title = "Auto-connect on launch",
            subtitle = "Reconnect to the last paired device automatically",
            checked = app.autoConnect,
            onCheckedChange = onAutoConnect
        )
        SectionDivider()
        SwitchRow(
            title = "Simulator mode",
            subtitle = "Use generated telemetry instead of a real device",
            checked = app.simulatorMode,
            onCheckedChange = onSimulatorMode
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  2b. Phone notifications                                                    */
/* -------------------------------------------------------------------------- */

@Composable
private fun NotificationsSection(
    app: AppSettings,
    accessGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenAccess: () -> Unit,
    batteryExempt: Boolean,
    onRequestBatteryExemption: () -> Unit
) {
    SettingsSection(title = "Phone notifications") {
        SwitchRow(
            title = "Mirror to vehicle display",
            subtitle = "Push calls & messages to the board's screen",
            checked = app.notificationRelayEnabled && accessGranted,
            enabled = accessGranted,
            onCheckedChange = onToggle
        )
        SectionDivider()
        ActionRow(
            title = if (accessGranted) "Notification access granted" else "Grant notification access",
            subtitle = if (accessGranted) "Tap to review in system settings"
                else "Required — allow this app to read notifications",
            leadingTint = if (accessGranted) EvGreen else EvAmber,
            onClick = onOpenAccess
        )
        // Doze can unbind the notification listener mid-ride; the exemption keeps
        // relaying alive with the screen off. Hidden once granted.
        if (!batteryExempt) {
            SectionDivider()
            ActionRow(
                title = "Allow background delivery",
                subtitle = "Exempt from battery optimization so relaying survives Doze",
                leadingTint = EvAmber,
                onClick = onRequestBatteryExemption
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  3. Storage                                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun StorageSection(
    storage: StorageInfo?,
    onClearTrips: () -> Unit
) {
    var confirmTrips by remember { mutableStateOf(false) }

    SettingsSection(title = "Storage") {
        ValueRow(
            title = "Database",
            value = storage?.let { it.format(it.databaseBytes) } ?: "—",
            subtitle = storage?.let { "${it.tripCount} trips · ${it.telemetrySampleCount} samples" }
        )
        SectionDivider()
        ActionRow(
            title = "Clear trip history",
            subtitle = "Removes all trips and telemetry — cannot be undone",
            leadingTint = EvRed,
            onClick = { confirmTrips = true }
        )
    }

    if (confirmTrips) {
        ConfirmDialog(
            title = "Clear all trip history?",
            message = "This permanently deletes every recorded trip and its telemetry. " +
                "Already-exported CSVs are kept.",
            confirmLabel = "Delete",
            onDismiss = { confirmTrips = false },
            onConfirm = onClearTrips
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  6. Permissions                                                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun PermissionsSection(state: SettingsUiState, onManage: () -> Unit) {
    SettingsSection(title = "Permissions") {
        state.permissions.forEachIndexed { index, perm ->
            if (index > 0) SectionDivider()
            PermissionRow(
                name = perm.key.displayName,
                rationale = perm.key.rationale,
                granted = perm.state == PermissionState.GRANTED,
                notApplicable = perm.state == PermissionState.NOT_APPLICABLE,
                onManage = onManage
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  7. About                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun AboutSection(
    state: SettingsUiState,
    devUnlocked: Boolean,
    onUnlock: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    // Tap-7 unlock counter — resets after a short window if the user pauses.
    var tapCount by remember { mutableIntStateOf(0) }
    val unlockThreshold = 7
    LaunchedEffect(tapCount) {
        if (tapCount in 1 until unlockThreshold) {
            kotlinx.coroutines.delay(2_000L)
            tapCount = 0
        }
    }

    SettingsSection(title = "About") {
        // Version row is the tap-7 unlock target. Locked → counts taps;
        // unlocked → just a value row (no further behavior).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !devUnlocked) {
                    val next = tapCount + 1
                    tapCount = next
                    if (next >= unlockThreshold) {
                        tapCount = 0
                        onUnlock()
                    }
                }
        ) {
            ValueRow(title = "Version", value = state.appVersion.ifBlank { "—" })
        }

        if (devUnlocked) {
            SectionDivider()
            ActionRow(
                title = "Developer options",
                subtitle = "Advanced diagnostics, simulator, experimental",
                onClick = onOpenDeveloper
            )
        }
    }
}
