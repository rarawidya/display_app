package com.innodrive.evdash.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.innodrive.evdash.data.permissions.AppPermission
import com.innodrive.evdash.data.permissions.PermissionState
import com.innodrive.evdash.data.permissions.PermissionStatusProvider
import com.innodrive.evdash.data.persistence.StorageInfo
import com.innodrive.evdash.data.preferences.SavedDevice
import com.innodrive.evdash.domain.model.AppSettings
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.model.RetentionPeriod
import com.innodrive.evdash.domain.model.ThemeMode
import com.innodrive.evdash.presentation.state.SettingsUiState
import com.innodrive.evdash.service.NotificationRelayService
import com.innodrive.evdash.presentation.ui.icons.EvIcons
import com.innodrive.evdash.presentation.ui.settings.components.ActionRow
import com.innodrive.evdash.presentation.ui.settings.components.ChoiceRow
import com.innodrive.evdash.presentation.ui.settings.components.ConfirmDialog
import com.innodrive.evdash.presentation.ui.settings.components.PermissionRow
import com.innodrive.evdash.presentation.ui.settings.components.SectionDivider
import com.innodrive.evdash.presentation.ui.settings.components.SettingsSection
import com.innodrive.evdash.presentation.ui.settings.components.SwitchRow
import com.innodrive.evdash.presentation.ui.settings.components.ValueRow
import com.innodrive.evdash.presentation.viewmodel.SettingsViewModel
import com.innodrive.evdash.ui.theme.Dim
import com.innodrive.evdash.ui.theme.EvAmber
import com.innodrive.evdash.ui.theme.EvGreen
import com.innodrive.evdash.ui.theme.EvRed
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    permissionProvider: PermissionStatusProvider,
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wifiSendResult by viewModel.wifiSendResult.collectAsStateWithLifecycle()
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
                // an install/update) so relaying starts without a reboot. If access
                // is granted but the listener is still NOT connected — the "mirrors
                // calls but not WhatsApp messages" case the firmware audit isolated
                // to a dead listener (docs/NOTIFICATION-DISPLAY-DEBUG-RESPONSE.md) —
                // escalate to the component-cycle force-rebind, which recovers a
                // binding that `requestRebind` alone can't after an app update.
                if (notificationAccess) {
                    if (NotificationRelayService.isConnected) {
                        NotificationRelayService.requestRebindIfGranted(context)
                    } else {
                        NotificationRelayService.forceRebind(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Answering calls needs two runtime permissions, requested ONE AT A TIME so each
    // gets its own system dialog: READ_PHONE_STATE (call *mirroring* — calls don't
    // reach the notification listener) and, on API 26+, ANSWER_PHONE_CALLS (the
    // cluster's Answer/End buttons). They must NOT be batched: ANSWER_PHONE_CALLS is
    // not granted by the shared "Phone" group dialog on modern Android / One UI, so a
    // batched request silently leaves it denied (no USER_SET). The relay toggle is
    // enabled either way — a denial just means notifications-only / no call control.
    var showAnswerCallsSettings by remember { mutableStateOf(false) }

    val answerCallsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setNotificationRelayEnabled(true)
        // Denied here with rationale no longer allowed = permanent denial or a dialog
        // the OS suppressed (the One UI case). The only path left to grant it is the
        // app's system permission page — offer a one-tap route there.
        if (!granted) {
            val canRetry = context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.ANSWER_PHONE_CALLS
                )
            } ?: false
            if (!canRetry) showAnswerCallsSettings = true
        }
    }

    // After READ_PHONE_STATE resolves, chain the separate ANSWER_PHONE_CALLS ask (its
    // own dialog); if it's already granted or the device is pre-O, just enable relay.
    val requestAnswerCallsOrEnable: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            answerCallsLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS)
        } else {
            viewModel.setNotificationRelayEnabled(true)
        }
    }

    val phoneStateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestAnswerCallsOrEnable() }

    SettingsContent(
        state = state,
        notificationAccessGranted = notificationAccess,
        onBack = onBack,
        onThemeMode = viewModel::setThemeMode,
        onRetention = viewModel::setRetention,
        onAutoConnect = viewModel::setAutoConnect,
        onForgetDevice = viewModel::forgetDevice,
        onSimulatorMode = viewModel::setSimulatorMode,
        onNotificationRelay = { enabled ->
            when {
                !enabled -> viewModel.setNotificationRelayEnabled(false)
                // Request READ_PHONE_STATE first; its result callback chains the
                // separate ANSWER_PHONE_CALLS ask so each shows its own dialog.
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED ->
                    phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                else -> requestAnswerCallsOrEnable()
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
        },
        wifiSendResult = wifiSendResult,
        onWifiResultShown = viewModel::consumeWifiSendResult,
        onSaveHotspotCredentials = viewModel::setHotspotCredentials,
        onForgetBoardWifi = viewModel::forgetBoardWifi,
        onOpenHotspotSettings = {
            // There is no public tethering-settings action; the hidden one resolves
            // on most builds (incl. One UI). Fall back to the wireless page. The app
            // cannot toggle the hotspot itself — Android reserves that for system apps.
            val tether = Intent("android.settings.TETHER_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(tether) }.onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    )

    if (showAnswerCallsSettings) {
        ConfirmDialog(
            title = "Allow answering calls",
            message = "To answer or end calls from the vehicle display's buttons, grant the " +
                "\"Answer phone calls\" permission for this app in system settings.",
            confirmLabel = "Open Settings",
            cancelLabel = "Not now",
            onDismiss = { showAnswerCallsSettings = false },
            onConfirm = {
                showAnswerCallsSettings = false
                context.startActivity(permissionProvider.appDetailsIntent())
            }
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

/** Unwrap the composition [Context] to its hosting [Activity] (needed for
 *  `shouldShowRequestPermissionRationale`), tolerating ContextWrapper nesting. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    notificationAccessGranted: Boolean,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onRetention: (RetentionPeriod) -> Unit,
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
    onManagePermissions: () -> Unit,
    wifiSendResult: String? = null,
    onWifiResultShown: () -> Unit = {},
    onSaveHotspotCredentials: (String, String) -> Unit = { _, _ -> },
    onForgetBoardWifi: () -> Unit = {},
    onOpenHotspotSettings: () -> Unit = {}
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // One-shot outcome of a board Wi-Fi action → snackbar, then consumed.
    LaunchedEffect(wifiSendResult) {
        wifiSendResult?.let {
            snackbar.showSnackbar(it)
            onWifiResultShown()
        }
    }

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
                VehicleInternetSection(
                    app = state.app,
                    onSave = onSaveHotspotCredentials,
                    onForget = onForgetBoardWifi,
                    onOpenHotspotSettings = onOpenHotspotSettings
                )
                StorageSection(
                    storage = state.storage,
                    app = state.app,
                    onRetention = onRetention,
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
    theme: com.innodrive.evdash.domain.model.ThemeSettings,
    onThemeMode: (ThemeMode) -> Unit
) {
    SettingsSection(title = "Appearance") {
        ThemeSelector(selected = theme.mode, onSelected = onThemeMode)
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.LIGHT  -> "Light"
    ThemeMode.DARK   -> "Dark"
    ThemeMode.SYSTEM -> "System"
}
private fun themeDescription(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT  -> "Daytime"
    ThemeMode.DARK   -> "Night driving"
    ThemeMode.SYSTEM -> "Match phone"
}

/**
 * Visual theme picker: a row of tappable preview cards, each rendering a mini cockpit
 * mock in that theme's own palette so the choice is shown, not just labelled. The
 * selected card gets an accent ring + tinted label.
 */
@Composable
private fun ThemeSelector(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Dim.md),
        horizontalArrangement = Arrangement.spacedBy(Dim.sm)
    ) {
        ThemeMode.entries.forEach { mode ->
            ThemeCard(
                mode = mode,
                selected = mode == selected,
                onClick = { onSelected(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeCard(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val ringColor = if (selected) accent else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(Dim.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = ringColor,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
        ) {
            ThemeMock(mode)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = themeLabel(mode),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = themeDescription(mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Representative per-theme palette for the preview mock (independent of the live theme). */
private data class MockPalette(val bg: Color, val surface: Color, val line: Color, val accent: Color)
private val LIGHT_MOCK = MockPalette(Color(0xFFEDF1F6), Color(0xFFFFFFFF), Color(0xFFCAD3DE), Color(0xFF2F6BFF))
private val DARK_MOCK  = MockPalette(Color(0xFF0F1218), Color(0xFF20262F), Color(0xFF39424E), Color(0xFF5B9CFF))

/** Tiny "cockpit screen" mock — gauge + tiles — in [mode]'s palette; a diagonal split for System. */
@Composable
private fun ThemeMock(mode: ThemeMode) {
    when (mode) {
        ThemeMode.LIGHT -> MockScreen(LIGHT_MOCK)
        ThemeMode.DARK  -> MockScreen(DARK_MOCK)
        ThemeMode.SYSTEM -> Box(Modifier.fillMaxSize()) {
            // Diagonal split: light top-left, dark bottom-right.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to LIGHT_MOCK.bg, 0.5f to LIGHT_MOCK.bg,
                        0.5f to DARK_MOCK.bg, 1f to DARK_MOCK.bg,
                    )
                )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.5f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .border(2.5.dp, DARK_MOCK.accent, CircleShape)
            )
        }
    }
}

@Composable
private fun MockScreen(p: MockPalette) {
    Column(
        modifier = Modifier.fillMaxSize().background(p.bg).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Title/status bar
        Box(Modifier.fillMaxWidth(0.6f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.line))
        // Gauge
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
                    .clip(CircleShape)
                    .background(p.surface)
                    .border(3.dp, p.accent, CircleShape)
            )
        }
        Spacer(Modifier.weight(1f))
        // Two tiles
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(p.surface))
            Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(p.surface))
        }
    }
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

        // Device + live status in one row (the standalone "Status" row was redundant
        // with the always-visible Drive/Home header indicator).
        if (savedDevice != null) {
            ValueRow(
                title = savedDevice.name,
                value = statusLabel,
                subtitle = savedDevice.address,
                leadingIcon = EvIcons.Bluetooth,
                valueColor = statusColor
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
                value = statusLabel,
                subtitle = "No device saved · pair from the Drive screen",
                leadingIcon = EvIcons.Bluetooth,
                valueColor = statusColor
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
/*  2c. Vehicle internet — phone hotspot → board Wi-Fi STA                     */
/*      (docs/BOARD-WIFI-STA-INTEGRATION.md)                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun VehicleInternetSection(
    app: AppSettings,
    onSave: (String, String) -> Unit,
    onForget: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    var showEdit by remember { mutableStateOf(false) }
    val configured = app.hotspotSsid.isNotBlank()

    SettingsSection(title = "Vehicle internet") {
        ActionRow(
            title = if (configured) "Hotspot: ${app.hotspotSsid}" else "Set hotspot credentials",
            // The header hotspot button now sends these to the display automatically,
            // so this is just where you enter/edit them — no separate "send" step.
            subtitle = if (configured) "Tap to edit — the hotspot button sends these to the display"
                else "The display joins your phone's hotspot to download maps",
            onClick = { showEdit = true }
        )
        SectionDivider()
        ActionRow(
            title = "Open hotspot settings",
            subtitle = "Turn the phone's hotspot on for the download",
            onClick = onOpenHotspotSettings
        )
        if (configured) {
            SectionDivider()
            ActionRow(
                title = "Clear Wi-Fi from display",
                subtitle = "Wipe the stored credentials on the vehicle display",
                leadingTint = EvRed,
                onClick = onForget
            )
        }
    }

    if (showEdit) {
        HotspotCredentialsDialog(
            initialSsid = app.hotspotSsid,
            initialPassword = app.hotspotPassword,
            onSave = { ssid, psk ->
                onSave(ssid, psk)
                showEdit = false
            },
            onDismiss = { showEdit = false }
        )
    }
}

@Composable
private fun HotspotCredentialsDialog(
    initialSsid: String,
    initialPassword: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var ssid by remember { mutableStateOf(initialSsid) }
    var psk by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phone hotspot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dim.sm)) {
                Text(
                    text = "The vehicle display joins this hotspot to download maps. " +
                        "Use your hotspot's own password — it is sent to the display " +
                        "over Bluetooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Hotspot name (SSID)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = psk,
                    onValueChange = { psk = it },
                    label = { Text("Password") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ssid, psk) }, enabled = ssid.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/* -------------------------------------------------------------------------- */
/*  3. Storage                                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun StorageSection(
    storage: StorageInfo?,
    app: AppSettings,
    onRetention: (RetentionPeriod) -> Unit,
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
        ChoiceRow(
            title = "Keep trip history",
            options = RetentionPeriod.entries,
            selected = app.retention,
            onSelected = onRetention,
            labelFor = { it.label },
            descriptionFor = {
                if (it == RetentionPeriod.FOREVER) "Never auto-delete recorded trips"
                else "Older trips are pruned automatically"
            }
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
