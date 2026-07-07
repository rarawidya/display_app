package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.navigation.NavigationCoordinator
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.GeoPlace
import com.example.displayapp.domain.model.Route
import com.example.displayapp.domain.model.RoutePlan
import com.example.displayapp.domain.repository.Geocoder
import com.example.displayapp.domain.repository.LocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.displayapp.presentation.state.MapsUiState

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
    private val geocoder: Geocoder,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    /**
     * Live place-search results (real geocoding), debounced on the query and biased to
     * the current location. Empty when the query is blank or the geocoder isn't
     * configured. The Navigation screen renders these instead of a hardcoded list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<GeoPlace>> = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else flow { emit(geocoder.search(q, near = uiState.value.currentLocation)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Name/address of the pending (previewed) destination, for the confirmation sheet.
    private val _pendingPlace = MutableStateFlow<GeoPlace?>(null)

    val uiState: StateFlow<MapsUiState> = combine(
        locationRepository.location,
        _searchQuery,
        _pendingPlace,
        coordinator.previewDestination,
        combine(coordinator.previewRoute, coordinator.activeRoute) { p, a -> p to a },
    ) { loc, query, place, pendingDest, routes ->
        val (previewRoute, activeRoute) = routes
        val navigating = activeRoute != null
        val previewing = pendingDest != null && !navigating
        // Draw the active route once navigating, otherwise the preview route.
        val drawn = (activeRoute ?: previewRoute)?.toRoute()
        MapsUiState(
            currentLocation = loc,
            searchQuery = query,
            destination = activeRoute?.destination ?: pendingDest,
            route = drawn,
            previewing = previewing,
            previewPlanning = previewing && previewRoute == null,
            previewName = place?.name.orEmpty(),
            previewDetail = place?.detail.orEmpty(),
            navigating = navigating,
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

    /** Pick a searched place → PREVIEW it (plan + show the confirmation sheet). */
    fun selectPlace(place: GeoPlace) {
        _searchQuery.value = ""
        _pendingPlace.value = place
        coordinator.preview(place.location)
    }

    /**
     * Long-press the map → PREVIEW a dropped pin. Shows a placeholder name immediately,
     * then reverse-geocodes for the real address. Does NOT start navigation.
     */
    fun dropPin(location: GeoLocation) {
        _searchQuery.value = ""
        _pendingPlace.value = GeoPlace(
            name = "Dropped pin",
            detail = "%.5f, %.5f".format(location.latitude, location.longitude),
            location = location,
        )
        coordinator.preview(location)
        viewModelScope.launch {
            geocoder.reverse(location)?.let { resolved ->
                // Only apply if still previewing this same pin.
                if (_pendingPlace.value?.location == location) _pendingPlace.value = resolved
            }
        }
    }

    /** Confirm the previewed destination → start the navigation session. */
    fun startNavigation() {
        coordinator.startPending()
        _pendingPlace.value = null
    }

    /** Back out of the confirmation without navigating. */
    fun dismissPreview() {
        coordinator.dismissPreview()
        _pendingPlace.value = null
    }

    fun clearRoute() {
        coordinator.cancel()
        _pendingPlace.value = null
        _searchQuery.value = ""
    }

    private fun RoutePlan.toRoute() = Route(
        origin = origin,
        destination = destination,
        polyline = polyline,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
    )
}

class MapsViewModelFactory(
    private val locationRepository: LocationRepository,
    private val coordinator: NavigationCoordinator,
    private val geocoder: Geocoder,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapsViewModel::class.java)) {
            return MapsViewModel(locationRepository, coordinator, geocoder) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
