package com.example.displayapp.presentation.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.displayapp.DisplayApp
import com.example.displayapp.data.bluetooth.ble.BleGattProbe
import com.example.displayapp.data.diagnostics.DiagSeverity
import com.example.displayapp.data.diagnostics.DiagnosticsLogEntry
import com.example.displayapp.data.diagnostics.DiagnosticsSnapshot
import com.example.displayapp.data.format.Formatters
import com.example.displayapp.data.simulator.TelemetryScenario
import com.example.displayapp.domain.model.AppSettings
import com.example.displayapp.domain.model.TimeFormat
import com.example.displayapp.presentation.state.SettingsUiState
import com.example.displayapp.presentation.ui.common.LocalAppSettings
import com.example.displayapp.presentation.ui.icons.EvIcons
import com.example.displayapp.presentation.ui.settings.components.ActionRow
import com.example.displayapp.presentation.ui.settings.components.ChoiceRow
import com.example.displayapp.presentation.ui.settings.components.SectionDivider
import com.example.displayapp.presentation.ui.settings.components.SettingsSection
import com.example.displayapp.presentation.ui.settings.components.SwitchRow
import com.example.displayapp.presentation.viewmodel.SettingsViewModel
import com.example.displayapp.ui.theme.Dim
import com.example.displayapp.ui.theme.EvAmber
import com.example.displayapp.ui.theme.EvRed
import kotlinx.coroutines.launch

/**
 * Hidden Developer Mode surface.
 *
 * Reached only after the user taps the Settings → About → Version row 7 times.
 * Houses everything that helps developers + power users but would distract a
 * normal rider: diagnostics counters & log, simulator scenario, dynamic color
 * (Material You), and the "Lock developer mode" exit.
 */
@Composable
fun DeveloperScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DeveloperContent(
        state = state,
        onBack = onBack,
        onShowOverlay = viewModel::setShowDiagnosticsOverlay,
        onResetDiagnostics = viewModel::resetDiagnostics,
        onSimulatorScenario = viewModel::setSimulatorScenario,
        onDynamicColor = viewModel::setDynamicColor,
        onLock = {
            viewModel.lockDeveloperMode()
            onBack()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onShowOverlay: (Boolean) -> Unit,
    onResetDiagnostics: () -> Unit,
    onSimulatorScenario: (TelemetryScenario) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLock: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Developer options",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Internal · not for normal riders",
                            style = MaterialTheme.typography.bodySmall,
                            color = EvAmber
                        )
                    }
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
                ExperimentalSection(
                    theme = state.theme,
                    onDynamicColor = onDynamicColor
                )
                BleProbeSection()
                BleTransportSection()
                SimulatorSection(
                    app = state.app,
                    onScenario = onSimulatorScenario
                )
                DiagnosticsSection(
                    app = state.app,
                    diagnostics = state.diagnostics,
                    onShowOverlay = onShowOverlay,
                    onReset = onResetDiagnostics
                )
                LogSection(logs = state.diagnosticsLogs, timeFormat = LocalAppSettings.current.timeFormat)
                ExitSection(onLock = onLock)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Experimental                                                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun ExperimentalSection(
    theme: com.example.displayapp.domain.model.ThemeSettings,
    onDynamicColor: (Boolean) -> Unit
) {
    SettingsSection(title = "Experimental") {
        SwitchRow(
            title = "Dynamic color (Material You)",
            subtitle = "Derive theme accents from wallpaper · Android 12+ · " +
                "clashes with the EV-blue brand palette",
            checked = theme.useDynamicColor,
            onCheckedChange = onDynamicColor
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  BLE GATT probe — reads the controller's GATT profile off real hardware.    */
/*  Diagnostic only; does not touch the live SPP telemetry pipeline.           */
/* -------------------------------------------------------------------------- */

@Composable
private fun BleProbeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe = remember { BleGattProbe(context.applicationContext) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<BleGattProbe.ScanEntry>>(emptyList()) }
    var report by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<BleGattProbe.CharRef>>(emptyList()) }
    var probedAddress by remember { mutableStateOf<String?>(null) }
    var listenReport by remember { mutableStateOf<String?>(null) }

    SettingsSection(title = "BLE GATT probe") {
        ActionRow(
            title = if (busy) "Working…" else "Scan for BLE devices (5s)",
            subtitle = "Discovers advertising peripherals — tap one to dump its GATT table",
            onClick = {
                if (busy) return@ActionRow
                busy = true; status = null; report = null; candidates = emptyList()
                listenReport = null; results = emptyList()
                scope.launch {
                    if (!probe.isBluetoothOn) {
                        status = "Turn on Bluetooth first."
                        busy = false
                        return@launch
                    }
                    val found = probe.scan(5_000)
                    results = found
                    status = if (found.isEmpty()) "No BLE devices found. Is the controller advertising?"
                             else "${found.size} device(s) found — tap to probe."
                    busy = false
                }
            }
        )

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        results.forEach { entry ->
            BleDeviceRow(entry) {
                if (busy) return@BleDeviceRow
                busy = true; report = null; candidates = emptyList(); listenReport = null
                probedAddress = entry.address
                status = "Probing ${entry.name}…"
                scope.launch {
                    val result = probe.probe(entry.address)
                    report = result.report
                    candidates = result.telemetryCandidates
                    status = null
                    busy = false
                }
            }
        }

        report?.let { text -> ProbeReportBox(text) }

        // One "Listen & confirm" action per detected telemetry candidate.
        candidates.forEach { ref ->
            ActionRow(
                title = "Listen 5s: ${uuidShort(ref.characteristic)}",
                subtitle = "Subscribe & decode through FrameDecoder to confirm telemetry",
                onClick = {
                    val addr = probedAddress ?: return@ActionRow
                    if (busy) return@ActionRow
                    busy = true; listenReport = null; status = "Listening on ${uuidShort(ref.characteristic)}…"
                    scope.launch {
                        listenReport = probe.listen(addr, ref, 5_000)
                        status = null
                        busy = false
                    }
                }
            )
        }

        listenReport?.let { text -> ProbeReportBox(text) }
    }
}

@Composable
private fun ProbeReportBox(text: String) {
    SectionDivider()
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}

/** "0000af08-…-34fb" → "0xAF08"; else first UUID segment. */
private fun uuidShort(u: java.util.UUID): String {
    val s = u.toString()
    return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
        "0x${s.substring(4, 8).uppercase()}"
    else s.substringBefore('-')
}

/* -------------------------------------------------------------------------- */
/*  BLE transport (experimental) — routes the live pipeline over GATT.         */
/*  Phase 1 harness: proves the unchanged Repository/UI render BLE telemetry.   */
/* -------------------------------------------------------------------------- */

@Composable
private fun BleTransportSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as DisplayApp).appContainer }

    val connState by container.vehicleRepository.connectionState.collectAsStateWithLifecycle()
    val data by container.vehicleRepository.vehicleData.collectAsStateWithLifecycle()
    var bleOn by remember { mutableStateOf(container.useBleTransport) }

    SettingsSection(title = "BLE transport (experimental)") {
        SwitchRow(
            title = "Route telemetry over BLE",
            subtitle = "GATT ${uuidShortHex()} instead of SPP · dev only, SPP is default",
            checked = bleOn,
            onCheckedChange = {
                bleOn = it
                container.setBleTransport(it)
                scope.launch { container.devicePreferences.setBleTransport(it) }
            }
        )
        SectionDivider()
        ActionRow(
            title = "Connect over BLE",
            subtitle = "Scan for 0xAF00 → subscribe 0xAF08 (bypasses the foreground service)",
            onClick = {
                scope.launch {
                    container.setBleTransport(true)
                    bleOn = true
                    container.devicePreferences.setBleTransport(true)
                    container.switchDataSource(simulator = false)
                    container.vehicleRepository.connect("")
                }
            }
        )
        ActionRow(
            title = "Disconnect BLE",
            subtitle = "Stop the BLE session",
            onClick = { container.vehicleRepository.disconnect() }
        )
        SectionDivider()
        Text(
            text = "State: $connState\n" +
                "speed=${data.speed} km/h   batt=${data.batteryPercent}%   " +
                "${data.voltage} V   rpm=${data.rpm}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

private fun uuidShortHex(): String = "0xAF00/0xAF08"

@Composable
private fun BleDeviceRow(entry: BleGattProbe.ScanEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = entry.address + (if (entry.serviceUuids.isNotEmpty()) "  • ${entry.serviceUuids.size} svc" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "${entry.rssi} dBm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Simulator scenario                                                         */
/* -------------------------------------------------------------------------- */

private val userScenarios = listOf(
    TelemetryScenario.CITY_CRUISE,
    TelemetryScenario.HIGHWAY,
    TelemetryScenario.SPORT_MODE,
    TelemetryScenario.REGEN_BRAKING,
    TelemetryScenario.OVERHEATING,
    TelemetryScenario.LOW_BATTERY,
    TelemetryScenario.PARKED
)
private fun scenarioLabel(s: TelemetryScenario) = when (s) {
    TelemetryScenario.CITY_CRUISE   -> "City cruise"
    TelemetryScenario.HIGHWAY       -> "Highway"
    TelemetryScenario.SPORT_MODE    -> "Sport"
    TelemetryScenario.REGEN_BRAKING -> "Regen braking"
    TelemetryScenario.OVERHEATING   -> "Overheat"
    TelemetryScenario.LOW_BATTERY   -> "Low battery"
    TelemetryScenario.PARKED        -> "Parked"
}
private fun scenarioDescription(s: TelemetryScenario) = when (s) {
    TelemetryScenario.CITY_CRUISE   -> "Stop-and-go traffic patterns"
    TelemetryScenario.HIGHWAY       -> "Sustained high-speed cruise"
    TelemetryScenario.SPORT_MODE    -> "Aggressive throttle and high RPM"
    TelemetryScenario.REGEN_BRAKING -> "Deceleration with battery recovery"
    TelemetryScenario.OVERHEATING   -> "Thermal-warning trace"
    TelemetryScenario.LOW_BATTERY   -> "Below-20% battery trace"
    TelemetryScenario.PARKED        -> "Zero-velocity idle"
}

@Composable
private fun SimulatorSection(
    app: AppSettings,
    onScenario: (TelemetryScenario) -> Unit
) {
    SettingsSection(title = "Simulator") {
        ChoiceRow(
            title = "Scenario",
            options = userScenarios,
            selected = app.simulatorScenario,
            onSelected = onScenario,
            labelFor = { scenarioLabel(it) },
            descriptionFor = { scenarioDescription(it) }
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Diagnostics counters                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun DiagnosticsSection(
    app: AppSettings,
    diagnostics: DiagnosticsSnapshot,
    onShowOverlay: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    SettingsSection(title = "Diagnostics") {
        SwitchRow(
            title = "Show overlay on Drive",
            subtitle = "Live FPS / CRC / sync counters pinned to the cockpit",
            checked = app.showDiagnosticsOverlay,
            onCheckedChange = onShowOverlay
        )
        SectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Counter("FPS", diagnostics.framesPerSecond.toString())
            Counter("Frames", diagnostics.framesDecoded.toString())
            Counter("CRC", diagnostics.crcErrors.toString(),
                accent = if (diagnostics.crcErrors > 0) EvAmber else null)
            Counter("Sync loss", diagnostics.syncLosses.toString(),
                accent = if (diagnostics.syncLosses > 0) EvRed else null)
            Counter("Reconn.", diagnostics.reconnects.toString())
        }
        SectionDivider()
        ActionRow(
            title = "Reset counters & log",
            subtitle = "Clears the in-memory diagnostics history",
            onClick = onReset
        )
    }
}

@Composable
private fun Counter(label: String, value: String, accent: Color? = null) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Recent events log                                                          */
/* -------------------------------------------------------------------------- */

@Composable
private fun LogSection(logs: List<DiagnosticsLogEntry>, timeFormat: TimeFormat) {
    SettingsSection(title = "Recent events") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (logs.isEmpty()) {
                Text(
                    text = "No diagnostics events recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                logs.take(20).forEach { entry ->
                    LogRow(entry, timeFormat)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: DiagnosticsLogEntry, timeFormat: TimeFormat) {
    val dotColor = when (entry.severity) {
        DiagSeverity.INFO  -> MaterialTheme.colorScheme.primary
        DiagSeverity.WARN  -> EvAmber
        DiagSeverity.ERROR -> EvRed
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = Formatters.time(entry.timestampMs, timeFormat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = entry.tag,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Exit                                                                       */
/* -------------------------------------------------------------------------- */

@Composable
private fun ExitSection(onLock: () -> Unit) {
    SettingsSection(title = "Exit") {
        ActionRow(
            title = "Lock developer mode",
            subtitle = "Hides this screen until you tap the Version row 7 times again",
            leadingTint = EvRed,
            onClick = onLock
        )
    }
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Developer mode is intended for engineering use. Toggles here may " +
                "change app behavior in ways that aren't supported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
