package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable

/**
 * Single vocabulary of plottable telemetry channels shared by Drive, Charts,
 * Trip Detail, and CSV. Adding a metric here threads it through everywhere
 * via [valueFrom] — no per-screen subset duplication.
 *
 * Visual identity (line color, legend dot, focus tint) lives in
 * `ui/theme/TelemetryPalette.kt`, not here. The enum stays free of any
 * Compose / theme dependency so non-UI callers (CSV export, repositories,
 * unit tests) can reference it without dragging in the design system.
 *
 * `fixedRange` pins the chart axis (battery 0–100) so the line doesn't
 * visually swing on minor noise.
 */
@Immutable
enum class TelemetryMetric(
    val displayName: String,
    val unit: String,
    val format: String,
    val fixedRange: ClosedFloatingPointRange<Float>? = null
) {
    Speed(         "Speed",           "km/h",  "%.0f"),
    Rpm(           "RPM",             "RPM",   "%.0f"),
    Voltage(       "Voltage",         "V",     "%.1f"),
    Current(       "Current",         "A",     "%.1f"),
    Power(         "Power",           "W",     "%.0f"),
    Battery(       "Battery",         "%",     "%.0f", 0f..100f),
    MotorTemp(     "Motor Temp",     "°C",    "%.0f"),
    BatteryTemp(   "Battery Temp",    "°C",    "%.0f"),
    ControllerTemp("Controller Temp", "°C",    "%.0f"),
    WhPerKm(       "Wh/km",           "Wh/km", "%.0f"),
    EstRange(      "Range",           "km",    "%.1f");

    /**
     * Pulls this metric's value out of the canonical [LiveTelemetry] snapshot.
     * Returns `null` when the value isn't available yet (rolling Wh/km / range
     * before enough distance has accumulated). Per-frame channels never null.
     */
    fun valueFrom(live: LiveTelemetry): Float? = when (this) {
        Speed          -> live.vehicle.speed.toFloat()
        Rpm            -> live.vehicle.rpm.toFloat()
        Voltage        -> live.vehicle.voltage
        Current        -> live.vehicle.current
        Power          -> live.vehicle.power
        Battery        -> live.vehicle.batteryPercent.toFloat()
        MotorTemp     -> live.vehicle.temperature.toFloat()
        BatteryTemp    -> live.vehicle.batteryTemperature.toFloat()
        ControllerTemp -> live.vehicle.controllerTemperature.toFloat()
        WhPerKm        -> live.efficiency.whPerKm
        EstRange       -> live.efficiency.rangeKm
    }

    /**
     * Whether this channel exists per-sample on `TelemetryEntity`. False for
     * rolling analytics (Wh/km, range) that derive from a window and are
     * computed on demand from the persisted base columns.
     */
    val isPersistedPerSample: Boolean
        get() = this != WhPerKm && this != EstRange

    companion object {
        /**
         * Metrics backed by a **real controller wire field** (capnp.md) — the only
         * ones the UI should plot. [Current] (and its derived [Power]) are now
         * plottable: `currentMotor @1` is calibrated signed deci-amps. Still
         * excluded: [WhPerKm]/[EstRange] (rolling-window analytics, not per-sample
         * series) and [BatteryTemp] (absent from the v1 wire, always 0). The enum
         * keeps those entries so persistence/CSV/decode stay intact; they just
         * never surface as selectable series.
         */
        val displayable: List<TelemetryMetric> =
            listOf(Speed, Rpm, Voltage, Current, Power, Battery, MotorTemp, ControllerTemp)
    }
}
