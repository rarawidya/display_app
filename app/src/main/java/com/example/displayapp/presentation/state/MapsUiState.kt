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
    val route: Route? = null,
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
