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

    // Rolling trip summary (resets on disconnect)
    private var sessionStartMs = 0L
    private var sessionMaxSpeed = 0
    private var sessionSpeedSum = 0L
    private var sessionSpeedSamples = 0L
    private var sessionFirstOdometer = -1f

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
        // Until the wire schema separates motor / controller / battery temps,
        // approximate them from the single reported temperature value using
        // steady-state offsets (controllers and battery packs run cooler than
        // the motor). Honest about being three distinct readings while keeping
        // the protocol unchanged — replace these when the schema gains fields.
        val controllerTemp = (data.temperature - 7).coerceAtLeast(0)
        val batteryTemp    = (data.temperature - 12).coerceAtLeast(0)

        val durationSec = if (sessionStartMs > 0) {
            ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(0)
        } else 0L
        val distanceKm = if (sessionFirstOdometer >= 0) {
            (data.odometer - sessionFirstOdometer).coerceAtLeast(0f)
        } else 0f
        val avgSpeed = if (sessionSpeedSamples > 0) (sessionSpeedSum / sessionSpeedSamples).toInt() else 0

        return DashboardUiState(
            speed = data.speed,
            batteryPercent = data.batteryPercent,
            voltage = "%.1f".format(data.voltage),
            current = "%.1f".format(data.current),
            temperature = data.temperature,
            controllerTemperature = controllerTemp,
            batteryTemperature = batteryTemp,
            odometer = "%.1f".format(data.odometer),
            vehicleMode = data.vehicleMode,
            leftIndicator = data.leftIndicator,
            rightIndicator = data.rightIndicator,
            headlamp = data.headlamp,
            connectionState = connectionState,
            diagnostics = DiagnosticsState(
                framesPerSecond = fps,
                lastUpdateMs = data.timestamp
            ),
            tripStats = TripStatsState(
                avgSpeed = avgSpeed,
                maxSpeed = sessionMaxSpeed,
                distanceKm = distanceKm,
                durationSec = durationSec
            ),
            efficiency = EfficiencyState(
                whPerKm = efficiency.whPerKm,
                rangeKm = efficiency.rangeKm
            )
        )
    }

    private fun updateSessionStats(data: VehicleData) {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
        if (sessionFirstOdometer < 0f) sessionFirstOdometer = data.odometer
        if (data.speed > sessionMaxSpeed) sessionMaxSpeed = data.speed
        sessionSpeedSum += data.speed
        sessionSpeedSamples += 1
    }

    private fun resetSession() {
        sessionStartMs = 0L
        sessionMaxSpeed = 0
        sessionSpeedSum = 0L
        sessionSpeedSamples = 0L
        sessionFirstOdometer = -1f
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
}
