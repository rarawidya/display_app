package com.innodrive.evdash.data.energy

import java.util.Locale

/**
 * Converts net energy consumption to a monetary cost. Hidden from the UI in
 * Phase 1 — the user has no way to set [ratePerKwh] yet — but the architecture
 * is ready so a future Settings input can flip cost visibility on.
 *
 * Phase 1: callers pass `ratePerKwh = null` and [formatCost] returns null,
 * which the EnergySummaryCard treats as "don't render this stat". When
 * Settings exposes the rate, callers pass it through and the cost stat
 * appears with no UI changes required.
 *
 * Currency formatting is intentionally plain (`$0.42`) — locale-aware
 * formatting is a separate concern once a real rate-input UI lands.
 */
object EnergyCostCalculator {

    /**
     * Cost in monetary units for [energyWh] consumed.
     * Returns null if rate is null/non-positive, or if energy is non-positive
     * (no meaningful cost to display).
     */
    fun computeCost(energyWh: Double, ratePerKwh: Float?): Double? {
        if (ratePerKwh == null || ratePerKwh <= 0f) return null
        if (energyWh <= 0.0) return null
        return (energyWh / 1000.0) * ratePerKwh.toDouble()
    }

    fun formatCost(energyWh: Double, ratePerKwh: Float?, currency: String = "$"): String? {
        val cost = computeCost(energyWh, ratePerKwh) ?: return null
        return String.format(Locale.US, "%s%.2f", currency, cost)
    }
}
