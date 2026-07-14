package com.innodrive.evdash.data.energy

import kotlin.math.absoluteValue

/**
 * Display formatting for energy / efficiency values.
 *
 * UI-only — keeps the calculation classes ([EnergyAccumulator],
 * [EfficiencyTracker]) free of localized strings and unit choices.
 *
 * Rules:
 *  - Energy auto-switches Wh → kWh when |value| ≥ 1000.
 *  - Efficiency renders one decimal under 100, no decimals at or above.
 *  - Range renders as integer km. Null / unknown → "—".
 */
object EnergyFormatter {

    /**
     * Format an energy value in Wh, auto-switching to kWh once the magnitude is
     * large enough that the extra precision wins over readability.
     *
     *   0.7 Wh → "0.7 Wh"
     *   42  Wh → "42 Wh"
     *   850 Wh → "850 Wh"
     *   1234 Wh → "1.23 kWh"
     *   12345 Wh → "12.3 kWh"
     */
    fun formatEnergyWh(wh: Double): String {
        val mag = wh.absoluteValue
        return when {
            mag >= 10_000.0 -> "%.1f kWh".format(wh / 1000.0)
            mag >= 1_000.0  -> "%.2f kWh".format(wh / 1000.0)
            mag >= 10.0     -> "%.0f Wh".format(wh)
            else            -> "%.1f Wh".format(wh)
        }
    }

    /**
     * Signed variant — used for "Net" so a regen-dominant trip displays as
     * negative.
     */
    fun formatNetEnergyWh(wh: Double): String {
        val sign = if (wh < 0.0) "−" else ""
        return sign + formatEnergyWh(wh.absoluteValue)
    }

    /** Wh/km efficiency. Null → em-dash. */
    fun formatEfficiency(whPerKm: Float?): String {
        if (whPerKm == null || whPerKm.isNaN() || whPerKm.isInfinite()) return "—"
        val mag = whPerKm.absoluteValue
        return when {
            mag >= 100f -> "%.0f".format(whPerKm)
            else        -> "%.1f".format(whPerKm)
        }
    }

    /** Estimated range in km. Null → em-dash. */
    fun formatRangeKm(km: Float?): String {
        if (km == null || km.isNaN() || km.isInfinite() || km < 0f) return "—"
        return "%.0f".format(km)
    }
}
