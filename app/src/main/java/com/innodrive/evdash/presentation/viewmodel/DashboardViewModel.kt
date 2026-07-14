package com.innodrive.evdash.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.innodrive.evdash.data.diagnostics.DiagnosticsRepository
import com.innodrive.evdash.data.diagnostics.DiagnosticsSnapshot
import com.innodrive.evdash.data.energy.EfficiencyTracker
import com.innodrive.evdash.data.notification.PhoneNotificationSender
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.data.protocol.TelemetryConstants
import com.innodrive.evdash.domain.model.BluetoothDeviceInfo
import com.innodrive.evdash.domain.model.ConnectionState
import com.innodrive.evdash.domain.model.DeviceInfo
import com.innodrive.evdash.domain.model.VehicleData
import com.innodrive.evdash.domain.model.controllerTypeName
import com.innodrive.evdash.domain.model.firmwareVersionName
import com.innodrive.evdash.domain.repository.AppPreferencesRepository
import com.innodrive.evdash.domain.repository.VehicleRepository
import com.innodrive.evdash.presentation.state.DashboardUiState
import com.innodrive.evdash.presentation.state.DiagnosticsState
import com.innodrive.evdash.presentation.state.EfficiencyState
import com.innodrive.evdash.presentation.state.TripStatsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the EV telemetry dashboard.
 *
 * Responsibilities:
 * - Combines domain flows into a single [DashboardUiState]
 * - Performs all formatting/derivation (the UI does zero computation)
 * - Tracks telemetry FPS for diagnostics
 * - Maintains a rolling per-session trip summary (avg/max speed, distance, duration)
 *
 * Uses [SharingStarted.WhileSubscribed(5000)] so the upstream flows
 * stay alive for 5s after the last subscriber disconnects (survives
 * quick config changes without losing connection).
 */
class DashboardViewModel(
    private val repository: VehicleRepository,
    private val efficiencyTracker: EfficiencyTracker,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val notificationSender: PhoneNotificationSender,
    private val appPreferences: AppPreferencesRepository
) : ViewModel() {

    // Trip B is app-tracked: displayed distance = odometer − baseline. The baseline
    // is the odometer snapshot at the last Trip B reset (persisted). `lastOdometerKm`
    // mirrors the current displayed odometer so resetTripB() can snapshot it.
    private var tripBBaselineKm = 0f
    private var lastOdometerKm = 0f

    // Latest DIS metadata (model / firmware). Read as a plain field rather than a
    // 6th combine input: it changes only on connect, and the combine re-emits on
    // every ~10 Hz frame, so the UI picks it up within one frame.
    private var deviceInfo: DeviceInfo? = null

    init {
        viewModelScope.launch {
            appPreferences.settings.collect { tripBBaselineKm = it.tripBBaselineKm }
        }
        viewModelScope.launch {
            repository.deviceInfo.collect { deviceInfo = it }
        }
    }

    private val _showDiagnostics = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = _showDiagnostics

    // FPS is computed here off *distinct wire frames* and pushed into
    // DiagnosticsRepository so the snapshot is the single canonical source —
    // Settings and the overlay read the same number. The upstream combine
    // re-emits whenever ANY input (efficiency/diag/rssi) ticks, not once per
    // frame, so we dedupe by vehicleData identity before counting; otherwise
    // FPS reads 2–3× the true rate.
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var lastCountedFrame: VehicleData? = null

    // Rolling trip summary (resets on disconnect).
    // Distance is integrated from speed × dt — schema-first telemetry has no
    // odometer wire field, so this is the canonical source of session distance.
    private var sessionStartMs = 0L
    private var sessionMaxSpeed = 0
    private var sessionSpeedSum = 0L
    private var sessionSpeedSamples = 0L
    private var sessionDistanceKm = 0.0
    private var sessionLastSpeed = 0
    private var sessionLastTimestampMs = 0L

    // Charging state with hysteresis (capnpble.md §3): on at ≥1 A into the pack,
    // off only after ≤0 A held ~1 s, so the icon doesn't flicker on the charger's
    // 0→1→4 A ramp. Timed off the frame timestamp (no wall-clock reads).
    private var charging = false
    private var chargingOffSinceMs = 0L

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.vehicleData,
        repository.connectionState,
        efficiencyTracker.state,
        diagnosticsRepository.snapshot,
        repository.rssi
    ) { vehicleData, connectionState, efficiency, diag, rssi ->
        trackFps(vehicleData)
        if (connectionState == ConnectionState.CONNECTED) updateSessionStats(vehicleData)
        if (connectionState == ConnectionState.DISCONNECTED) resetSession()
        updateCharging(vehicleData, connectionState)
        lastOdometerKm = displayedOdometerKm(vehicleData)
        mapToUiState(vehicleData, connectionState, diag, efficiency, rssi)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = repository.availableDevices

    fun startScan() { repository.startScan() }
    fun stopScan() { repository.stopScan() }

    fun connect(address: String) {
        viewModelScope.launch { repository.connect(address) }
    }

    fun disconnect() { repository.disconnect() }

    fun toggleDiagnostics() {
        _showDiagnostics.value = !_showDiagnostics.value
    }

    private fun mapToUiState(
        data: VehicleData,
        connectionState: ConnectionState,
        diag: DiagnosticsSnapshot,
        efficiency: EfficiencyTracker.State,
        rssi: Int?
    ): DashboardUiState {
        val durationSec = if (sessionStartMs > 0) {
            ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(0)
        } else 0L
        val avgSpeed = if (sessionSpeedSamples > 0) (sessionSpeedSum / sessionSpeedSamples).toInt() else 0

        // Pass canonical telemetry + diagnostics through; no UI-side math.
        // rpm/power are derived in TelemetryMapper; counters originate in
        // FrameDecoder → DiagnosticsRepository.
        return DashboardUiState(
            speed = data.speed,
            rpm = data.rpm,
            batteryPercent = data.batteryPercent,
            voltage = data.voltage,
            current = data.current,
            power = data.power,
            temperature = data.temperature,
            controllerTemperature = data.controllerTemperature,
            batteryTemperature = data.batteryTemperature,
            charging = charging,
            // Prefer the board-integrated lifetime odometer (odoMeters @12, persists
            // across reboots); fall back to the live session distance for firmware
            // that doesn't send it yet (odometerKm == 0).
            odometer = displayedOdometerKm(data),
            // Trip A = the vehicle's own resettable trip (tripMeters @13).
            tripOdometer = data.tripKm,
            // Trip B = app-tracked trip since the last local reset.
            tripBOdometer = (displayedOdometerKm(data) - tripBBaselineKm).coerceAtLeast(0f),
            vehicleMode = data.vehicleMode,
            // Warning-lamp telltales — decode the wire `flags` bitfield and
            // `faultCode` (capnp.md §3). No UI-side bit math downstream.
            engineRunning = data.flags and FLAG_ENGINE_RUNNING != 0,
            brakeActive = data.flags and FLAG_BRAKE != 0,
            reverseActive = data.flags and FLAG_REVERSE != 0,
            faultActive = data.faultCode != 0L,
            faultCode = data.faultCode,
            currentAvailable = data.currentAvailable,
            batteryTempAvailable = data.batteryTempAvailable,
            batteryKnown = data.batteryKnown,
            connectionState = connectionState,
            rssi = rssi,
            // Prefer the authoritative running firmware from the telemetry stream
            // (fwVersion @17) over the once-per-connect DIS revision string; fall
            // back to DIS when the board doesn't report it (0 → null).
            firmware = firmwareVersionName(data.firmwareVersion) ?: deviceInfo?.firmwareRevision,
            // Model: the DIS model number (0x2A24) is the specific vehicle model, so
            // keep it primary; fall back to the controller type (@23) when the board
            // exposes no DIS model but is streaming telemetry.
            model = deviceInfo?.modelNumber
                ?: (if (connectionState == ConnectionState.CONNECTED) controllerTypeName(data.controllerType) else null),
            diagnostics = DiagnosticsState(
                framesPerSecond = diag.framesPerSecond,
                framesDecoded = diag.framesDecoded,
                crcErrors = diag.crcErrors,
                syncLosses = diag.syncLosses,
                reconnects = diag.reconnects,
                notificationsPushed = diag.notificationsPushed,
                notificationsDropped = diag.notificationsDropped,
                // Prefer the live frame timestamp — it ticks every frame.
                // Falling back to diag.lastUpdateMs would lag by up to the
                // diagnostics-flush interval.
                lastUpdateMs = data.timestamp
            ),
            tripStats = TripStatsState(
                avgSpeed = avgSpeed,
                maxSpeed = sessionMaxSpeed,
                distanceKm = sessionDistanceKm.toFloat(),
                durationSec = durationSec
            ),
            // Wh/km and range derive from bus power; with the current channel
            // uncalibrated they'd read a misleading 0 / infinite range, so hold
            // them at "—" (null) until current is trustworthy.
            efficiency = EfficiencyState(
                whPerKm = if (data.currentAvailable) efficiency.whPerKm else null,
                rangeKm = if (data.currentAvailable) efficiency.rangeKm else null
            )
        )
    }

    private fun updateSessionStats(data: VehicleData) {
        if (sessionStartMs == 0L) {
            sessionStartMs = System.currentTimeMillis()
            sessionLastSpeed = data.speed
            sessionLastTimestampMs = data.timestamp
        }
        if (data.speed > sessionMaxSpeed) sessionMaxSpeed = data.speed
        sessionSpeedSum += data.speed
        sessionSpeedSamples += 1

        // Integrate distance from speed × dt (trapezoidal). Same shape as
        // TripSessionManager so Drive's "session distance" and Logs' trip
        // distance use the same algorithm.
        val dtMsRaw = data.timestamp - sessionLastTimestampMs
        if (sessionLastTimestampMs > 0L && dtMsRaw > 0L) {
            // CLAMP the gap (don't drop it): every other integrator — TripSessionManager,
            // EnergyAccumulator, EfficiencyTracker — clamps inter-sample dt to
            // MAX_SAMPLE_DT_MS and still integrates. Gating the whole interval out on a
            // >1 s gap made Drive's session distance drift below the recorded trip on any
            // BT stutter (CLAUDE.md: the two must differ only by aggregation window, never
            // by gap policy).
            val dtMs = dtMsRaw.coerceAtMost(TelemetryConstants.MAX_SAMPLE_DT_MS)
            // avgSpeedMs is metres/second; km = m/s × ms ÷ 1_000_000 (÷1000 ms→s,
            // ÷1000 m→km). Dividing by 3_600_000 (the km/h divisor) would apply the
            // 3.6 conversion twice and under-report distance 3.6×.
            val avgSpeedMs = ((sessionLastSpeed + data.speed) / 2.0) / 3.6
            sessionDistanceKm += avgSpeedMs * dtMs / 1_000_000.0
        }
        sessionLastSpeed = data.speed
        sessionLastTimestampMs = data.timestamp
    }

    private fun resetSession() {
        sessionStartMs = 0L
        sessionMaxSpeed = 0
        sessionSpeedSum = 0L
        sessionSpeedSamples = 0L
        sessionDistanceKm = 0.0
        sessionLastSpeed = 0
        sessionLastTimestampMs = 0L
        charging = false
        chargingOffSinceMs = 0L
    }

    /** Charging hysteresis — see the field docs. Off while disconnected. */
    private fun updateCharging(data: VehicleData, connectionState: ConnectionState) {
        if (connectionState != ConnectionState.CONNECTED) {
            charging = false
            chargingOffSinceMs = 0L
            return
        }
        val amps = data.batteryCurrent
        when {
            amps >= CHARGING_ON_AMPS -> { charging = true; chargingOffSinceMs = 0L }
            amps <= 0f && charging -> {
                if (chargingOffSinceMs == 0L) chargingOffSinceMs = data.timestamp
                else if (data.timestamp - chargingOffSinceMs >= CHARGING_OFF_HOLD_MS) {
                    charging = false
                    chargingOffSinceMs = 0L
                }
            }
        }
    }

    /** Lifetime odometer to display: board value when present, else session distance. */
    private fun displayedOdometerKm(data: VehicleData): Float =
        if (data.odometerKm > 0f) data.odometerKm else sessionDistanceKm.toFloat()

    /**
     * Reset **Trip A** — the vehicle's own trip — over BLE. Sends a `PhoneNotification`
     * control command (`category=32`, `title="ODO_RESET_TRIP"`) to `0xAF07`
     * (capnpble.md §5b). The lifetime odometer is untouched; `tripMeters` returns to
     * 0 on the next uplink. No-op if not connected (write returns false).
     */
    fun resetTripOdometer() {
        viewModelScope.launch {
            notificationSender.send(
                PhoneNotificationSchema.PhoneNotification(
                    id = 0,
                    category = PhoneNotificationSchema.CATEGORY_CONTROL,
                    appName = "EVD",
                    title = "ODO_RESET_TRIP"
                )
            )
        }
    }

    /**
     * Reset **Trip B** — the app-tracked trip — by snapshotting the current odometer
     * as the new baseline (persisted). Trip B then reads 0 and climbs from here.
     */
    fun resetTripB() {
        viewModelScope.launch { appPreferences.setTripBBaselineKm(lastOdometerKm) }
    }

    private companion object {
        // VotolTelemetry `flags` bit masks (capnpble.md §3).
        const val FLAG_ENGINE_RUNNING = 0x01
        const val FLAG_BRAKE = 0x02
        const val FLAG_REVERSE = 0x08

        // Charging hysteresis thresholds (capnpble.md §3).
        const val CHARGING_ON_AMPS = 1f
        const val CHARGING_OFF_HOLD_MS = 1_000L
    }

    private fun trackFps(frame: VehicleData) {
        // Count only distinct frames; combine re-emits on efficiency/diag/rssi
        // changes that reuse the same vehicleData instance.
        if (frame !== lastCountedFrame) {
            frameCount++
            lastCountedFrame = frame
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = (frameCount * 1000 / elapsed).toInt()
            diagnosticsRepository.reportFps(fps)
            frameCount = 0
            lastFpsTime = now
        }
    }

}
