package com.example.displayapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.displayapp.presentation.state.TelemetryMetric

/**
 * Single source of truth for per-metric color identity across Charts, Drive,
 * Logs, and Trip Replay. Drop this in via [LocalTelemetryPalette] and let
 * call sites resolve via [TelemetryMetric.seriesColor] — never hardcode a
 * series color anywhere else.
 *
 * Light vs dark have different tunings because saturated yellow/cyan that
 * pop on a near-black surface get muddy on a near-white one, and vice
 * versa. The contract: the same metric on both themes reads as the *same
 * color identity* (a viewer instantly maps yellow → Voltage) while staying
 * legible against the surrounding surface.
 *
 * Adding a new telemetry channel: add an entry to [TelemetryMetric], then
 * extend [SeriesColors] + both palettes. Future overlay types (thermal
 * warnings, fault outlines, regen) hang off [OverlayKind] so the visual
 * language scales without scattering more raw `Color()` literals.
 */
interface TelemetryPalette {
    fun colorOf(metric: TelemetryMetric): Color
    fun overlay(kind: OverlayKind): Color
}

enum class OverlayKind {
    Fault,        // hard-fault outline / error tick
    Warning,      // thermal yellow-line / soft-limit
    Regen,        // negative-power / charging segment
    Efficient,    // efficiency score badge
    SohGood,      // battery state-of-health > 90
    SohDegraded,  // SoH 70-90
    SohPoor       // SoH < 70
}

/**
 * The canonical per-metric color recipe. Each pair is (light-mode, dark-mode)
 * — the light variant is tuned darker/more saturated so it survives on a
 * white card; the dark variant is brighter so it survives on near-black.
 */
private data class SeriesColors(
    val speed: Color,
    val rpm: Color,
    val voltage: Color,
    val current: Color,
    val power: Color,
    val battery: Color,
    val engineTemp: Color,
    val batteryTemp: Color,
    val controllerTemp: Color,
    val whPerKm: Color,
    val estRange: Color
)

private val DarkSeries = SeriesColors(
    speed          = Color(0xFF22D3EE),  // cyan
    rpm            = Color(0xFFA855F7),  // purple
    voltage        = Color(0xFFFACC15),  // yellow
    current        = Color(0xFFFB923C),  // orange
    power          = Color(0xFFEF4444),  // red
    battery        = Color(0xFF22C55E),  // green
    engineTemp     = Color(0xFFEC4899),  // pink (was amber — collided with orange/current)
    batteryTemp    = Color(0xFFD946EF),  // fuchsia (was pink — freed for engineTemp)
    controllerTemp = Color(0xFF3B82F6),  // blue (was deep orange — collided with current)
    whPerKm        = Color(0xFF14B8A6),  // teal
    estRange       = Color(0xFFA3E635)   // light green
)

private val LightSeries = SeriesColors(
    speed          = Color(0xFF0891B2),  // deeper cyan
    rpm            = Color(0xFF7C3AED),  // deeper purple
    voltage        = Color(0xFFCA8A04),  // darker yellow (otherwise illegible on white)
    current        = Color(0xFFEA580C),  // deeper orange
    power          = Color(0xFFDC2626),  // deeper red
    battery        = Color(0xFF16A34A),  // deeper green
    engineTemp     = Color(0xFFDB2777),  // deeper pink (was amber)
    batteryTemp    = Color(0xFFA21CAF),  // deeper fuchsia (was pink)
    controllerTemp = Color(0xFF2563EB),  // deeper blue (was burnt-orange)
    whPerKm        = Color(0xFF0D9488),  // deeper teal
    estRange       = Color(0xFF65A30D)   // deeper lime
)

private fun SeriesColors.lookup(metric: TelemetryMetric): Color = when (metric) {
    TelemetryMetric.Speed          -> speed
    TelemetryMetric.Rpm            -> rpm
    TelemetryMetric.Voltage        -> voltage
    TelemetryMetric.Current        -> current
    TelemetryMetric.Power          -> power
    TelemetryMetric.Battery        -> battery
    TelemetryMetric.MotorTemp     -> engineTemp
    TelemetryMetric.BatteryTemp    -> batteryTemp
    TelemetryMetric.ControllerTemp -> controllerTemp
    TelemetryMetric.WhPerKm        -> whPerKm
    TelemetryMetric.EstRange       -> estRange
}

internal object DarkTelemetryPalette : TelemetryPalette {
    override fun colorOf(metric: TelemetryMetric): Color = DarkSeries.lookup(metric)
    override fun overlay(kind: OverlayKind): Color = when (kind) {
        OverlayKind.Fault       -> Color(0xFFFF4D4F)
        OverlayKind.Warning     -> Color(0xFFFFC400)
        OverlayKind.Regen       -> Color(0xFF34D399)
        OverlayKind.Efficient   -> Color(0xFF22D3EE)
        OverlayKind.SohGood     -> Color(0xFF22C55E)
        OverlayKind.SohDegraded -> Color(0xFFF59E0B)
        OverlayKind.SohPoor     -> Color(0xFFEF4444)
    }
}

internal object LightTelemetryPalette : TelemetryPalette {
    override fun colorOf(metric: TelemetryMetric): Color = LightSeries.lookup(metric)
    override fun overlay(kind: OverlayKind): Color = when (kind) {
        OverlayKind.Fault       -> Color(0xFFDC2626)
        OverlayKind.Warning     -> Color(0xFFD97706)
        OverlayKind.Regen       -> Color(0xFF059669)
        OverlayKind.Efficient   -> Color(0xFF0891B2)
        OverlayKind.SohGood     -> Color(0xFF16A34A)
        OverlayKind.SohDegraded -> Color(0xFFD97706)
        OverlayKind.SohPoor     -> Color(0xFFDC2626)
    }
}

val LocalTelemetryPalette = staticCompositionLocalOf<TelemetryPalette> { DarkTelemetryPalette }

/** Convenience accessor — `TelemetryMetric.Speed.seriesColor()`. */
@Composable
@ReadOnlyComposable
fun TelemetryMetric.seriesColor(): Color = LocalTelemetryPalette.current.colorOf(this)

/** Visual treatments shared by every chart so the dim/focus rules stay consistent. */
object TelemetrySeriesStyle {
    /**
     * Alpha for a selected-but-not-focused series. Lower = harder to confuse
     * with the focused line during glance analysis.
     */
    const val UNFOCUSED_ALPHA = 0.55f

    /** Selected + focused line gets a thicker stroke so it reads first. */
    const val FOCUSED_STROKE_DP = 3.4f
    const val UNFOCUSED_STROKE_DP = 1.8f

    /** Alpha used for the legend dot when the metric is currently disabled. */
    const val DISABLED_DOT_ALPHA = 0.30f
}
