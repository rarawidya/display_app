package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.domain.model.BluetoothDeviceInfo
import com.example.displayapp.domain.model.ConnectionState
import com.example.displayapp.domain.model.VehicleData
import com.example.displayapp.domain.repository.VehicleRepository
import com.example.displayapp.presentation.state.DashboardUiState
import com.example.displayapp.presentation.state.DiagnosticsState
import com.example.displayapp.presentation.state.EfficiencyState
import com.example.displayapp.presentation.state.TripStatsState
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
    private val efficiencyTracker: EfficiencyTracker
) : ViewModel() {

    private val _showDiagnostics = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = _showDiagnostics

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private val _fps = MutableStateFlow(0)

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

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.vehicleData,
        repository.connectionState,
        _fps,
        efficiencyTracker.state
    ) { vehicleData, connectionState, fps, efficiency ->
        trackFps()
        if (connectionState == ConnectionState.CONNECTED) updateSessionStats(vehicleData)
        if (connectionState == ConnectionState.DISCONNECTED) resetSession()
        mapToUiState(vehicleData, connectionState, fps, efficiency)
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
        fps: Int,
        efficiency: EfficiencyTracker.State
    ): DashboardUiState {
        val durationSec = if (sessionStartMs > 0) {
            ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(0)
        } else 0L
        val avgSpeed = if (sessionSpeedSamples > 0) (sessionSpeedSum / sessionSpeedSamples).toInt() else 0

        // Pass canonical telemetry through; no UI-side math. rpm/power are
        // derived in TelemetryMapper; battery/controller temps are real wire
        // channels (telemetry.capnp v2+).
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
            vehicleMode = data.vehicleMode,
            connectionState = connectionState,
            diagnostics = DiagnosticsState(
                framesPerSecond = fps,
                lastUpdateMs = data.timestamp
            ),
            tripStats = TripStatsState(
                avgSpeed = avgSpeed,
                maxSpeed = sessionMaxSpeed,
                distanceKm = sessionDistanceKm.toFloat(),
                durationSec = durationSec
            ),
            efficiency = EfficiencyState(
                whPerKm = efficiency.whPerKm,
                rangeKm = efficiency.rangeKm
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
        if (sessionLastTimestampMs > 0L && dtMsRaw in 1..MAX_SESSION_DT_MS) {
            val avgSpeedMs = ((sessionLastSpeed + data.speed) / 2.0) / 3.6
            sessionDistanceKm += avgSpeedMs * dtMsRaw / 3_600_000.0
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
    }

    private fun trackFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            _fps.value = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            lastFpsTime = now
        }
    }

    private companion object {
        // Skip distance accumulation for gaps longer than this — covers
        // backgrounding pauses, reconnects, etc. without fabricating distance.
        const val MAX_SESSION_DT_MS = 2_000L
    }
}
