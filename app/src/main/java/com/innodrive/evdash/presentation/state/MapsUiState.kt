package com.innodrive.evdash.presentation.state

import androidx.compose.runtime.Immutable
import com.innodrive.evdash.domain.model.ChargingStation
import com.innodrive.evdash.domain.model.GeoLocation
import com.innodrive.evdash.domain.model.Route

/**
 * UI state for the Maps / Navigation surface.
 *
 * @param currentLocation latest GPS fix, or null if not yet available
 * @param searchQuery what the user typed in the search bar
 * @param destination chosen destination (placeholder picked from canned list)
 * @param route resolved route (stub today; live Directions API later)
 * @param chargingStations future feature — currently always empty
 * @param permissionGranted true once the runtime FINE/COARSE_LOCATION grant exists
 */
@Immutable
data class MapsUiState(
    val currentLocation: GeoLocation? = null,
    val searchQuery: String = "",
    val destination: GeoLocation? = null,
    /** The route to draw — the preview route before confirming, or the active one. */
    val route: Route? = null,
    // ── Destination confirmation (preview → confirm → navigate) ──────────────
    /** A destination is pending confirmation → show the confirmation sheet. */
    val previewing: Boolean = false,
    /** Preview route still being planned → sheet shows "Calculating…". */
    val previewPlanning: Boolean = false,
    /** Preview planning failed (routing/network/no fix) → sheet shows error + Retry. */
    val previewFailed: Boolean = false,
    /** Human-readable failure reason (typed GraphHopper error / no fix); shown on the sheet. */
    val previewErrorMessage: String? = null,
    /** Destination name + address for the confirmation sheet. */
    val previewName: String = "",
    val previewDetail: String = "",
    /** A live navigation session is active → show the ongoing ETA/cancel card. */
    val navigating: Boolean = false,
    /** Temporary "Overview" during navigation → fit the route, then revert to follow. */
    val overviewActive: Boolean = false,
    /** Bumps whenever a fresh camera fit is wanted (new destination or Overview). */
    val fitToken: Int = 0,
    val chargingStations: List<ChargingStation> = emptyList(),
    val permissionGranted: Boolean = false,
    /**
     * Live remaining distance / ETA from the tracked [NavProgress] once navigating —
     * null before navigation (preview) or before the first fix arrives, in which case
     * the labels fall back to the route's static totals. Without this the ETA card
     * would freeze at the plan totals and never count down during the drive.
     */
    val remainingMeters: Int? = null,
    val etaSeconds: Int? = null,
    /** Current battery SoC (%), or null when disconnected / unknown. */
    val batteryPercentNow: Int? = null,
    /** Estimated battery (%) on arrival, or null when it can't be estimated yet. */
    val batteryAtArrivalPct: Int? = null,
    /** True once the rider reaches the destination (NavState.Arrived) → show the arrival card. */
    val arrived: Boolean = false,
    /** Destination label for the arrival card (from the active route), else empty. */
    val destinationName: String = ""
) {
    /** Driver-friendly distance label — live remaining while navigating, else route total. */
    val distanceLabel: String?
        get() = (remainingMeters ?: route?.distanceMeters)?.let {
            if (it >= 1000) "%.1f km".format(it / 1000f) else "$it m"
        }

    /** Driver-friendly ETA label — live while navigating, else route total. */
    val etaLabel: String?
        get() = (etaSeconds ?: route?.durationSeconds)?.let { sec ->
            val h = sec / 3600
            val m = (sec % 3600) / 60
            if (h > 0) "${h}h ${m}m" else "${m} min"
        }

    /** Battery value for the ETA card: estimated arrival charge, else current SoC, else —. */
    val batteryValue: String
        get() = (batteryAtArrivalPct ?: batteryPercentNow)?.let { "$it%" } ?: "—%"

    /** "now" only when showing the live SoC (no arrival estimate yet); "est" otherwise. */
    val batterySuffix: String
        get() = if (batteryAtArrivalPct == null && batteryPercentNow != null) "now" else "est"
}
