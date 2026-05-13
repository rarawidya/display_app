package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.domain.model.VehicleData

/**
 * Unified per-frame telemetry snapshot shared by every screen.
 *
 * `vehicle` carries the canonical CapNP-sourced values (after [com.example.displayapp.data.protocol.TelemetryMapper]
 * decode + derived `rpm` / `power`). `efficiency` carries the rolling
 * analytics that depend on a time window (`whPerKm`, `rangeKm`). Together
 * they cover every metric the UI surfaces — Drive tiles, Charts series,
 * Trip Detail headline cards.
 *
 * Pages must not invent their own subset of telemetry: read fields through
 * [TelemetryMetric.valueFrom] so a single enum edit threads a new channel
 * through Drive / Charts / Logs / Trip Detail / CSV consistently.
 */
@Immutable
data class LiveTelemetry(
    val vehicle: VehicleData = VehicleData(),
    val efficiency: EfficiencyTracker.State = EfficiencyTracker.State()
)
