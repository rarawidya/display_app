package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.navigation.NavigationCoordinator
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.Route
import com.example.displayapp.domain.repository.LocationRepository
import com.example.displayapp.presentation.state.MapsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Owns the Maps / Navigation UI state. The search query is local input; the
 * **destination, route geometry, and live progress all come from the shared
 * [NavigationCoordinator]** — the one navigation session. So the Drive mini-map and
 * the fullscreen Navigation screen render the SAME session that streams
 * `NavInstruction` frames to the controller (single source of truth).
 *
 * Routing is real now (GraphHopper via the coordinator's RoutePlanner) — no stub.
 * Shared as a singleton across Drive + Navigation via AppContainer so both surfaces
 * observe the same location, destination, and route.
 */
class MapsViewModel(
    private val locationRepository: LocationRepository,
    private val coordinator: NavigationCoordinator,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    val uiState: StateFlow<MapsUiState> = combine(
        locationRepository.location,
        _searchQuery,
        coordinator.activeRoute,
    ) { loc, query, plan ->
        MapsUiState(
            currentLocation = loc,
            searchQuery = query,
            destination = plan?.destination,
            // Provider-neutral RoutePlan → the UI's Route shape (polyline + ETA/distance).
            route = plan?.let {
                Route(
                    origin = it.origin,
                    destination = it.destination,
                    polyline = it.polyline,
                    distanceMeters = it.distanceMeters,
                    durationSeconds = it.durationSeconds,
                )
            },
            permissionGranted = loc != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapsUiState(),
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /**
     * Pick a destination → start the one navigation session. GraphHopper plans the
     * route; when it resolves, [NavigationCoordinator.activeRoute] updates and the map
     * draws the route while the BLE `NavInstruction` stream begins — the same
     * `NavProgress` drives both.
     */
    fun selectDestination(destination: GeoLocation, label: String = "") {
        if (label.isNotBlank()) _searchQuery.value = label
        coordinator.navigateTo(destination)
    }

    fun clearRoute() {
        coordinator.cancel()
        _searchQuery.value = ""
    }
}

class MapsViewModelFactory(
    private val locationRepository: LocationRepository,
    private val coordinator: NavigationCoordinator,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapsViewModel::class.java)) {
            return MapsViewModel(locationRepository, coordinator) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
