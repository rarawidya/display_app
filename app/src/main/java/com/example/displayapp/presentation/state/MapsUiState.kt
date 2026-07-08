package com.example.displayapp.presentation.state

import androidx.compose.runtime.Immutable
import com.example.displayapp.domain.model.ChargingStation
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.Route

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
    val permissionGranted: Boolean = false
) {
    /** Driver-friendly distance label — used by the ETA card. */
    val distanceLabel: String?
        get() = route?.distanceMeters?.let {
            if (it >= 1000) "%.1f km".format(it / 1000f) else "$it m"
        }

    /** Driver-friendly ETA label. */
    val etaLabel: String?
        get() = route?.durationSeconds?.let { sec ->
            val h = sec / 3600
            val m = (sec % 3600) / 60
            if (h > 0) "${h}h ${m}m" else "${m} min"
        }
}
