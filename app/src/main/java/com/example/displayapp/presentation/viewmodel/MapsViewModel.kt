package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.Route
import com.example.displayapp.domain.repository.LocationRepository
import com.example.displayapp.presentation.state.MapsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns the Maps / Navigation state.
 *
 * - Subscribes to the [LocationRepository] flow and folds each fix into [uiState].
 * - Exposes intent functions for search-bar input, destination selection, and
 *   route clearing. Routing today is a synchronous straight-line stub — swap
 *   in a real Directions API call here when the time comes.
 * - Shared as a singleton across Drive + Navigation screens via the AppContainer
 *   so they observe the same live location and selected destination.
 */
class MapsViewModel(
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _destination = MutableStateFlow<GeoLocation?>(null)
    private val _route       = MutableStateFlow<Route?>(null)

    val uiState: StateFlow<MapsUiState> = combine(
        locationRepository.location,
        _searchQuery,
        _destination,
        _route
    ) { loc, query, dest, route ->
        MapsUiState(
            currentLocation = loc,
            searchQuery = query,
            destination = dest,
            route = route,
            permissionGranted = loc != null   // any non-null fix implies permission
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapsUiState()
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /**
     * Pick a destination. In a real impl this would be the place selected from
     * a Places Autocomplete response. The route is computed via [computeRouteStub]
     * — replace that with a Directions API call when routing goes live.
     */
    fun selectDestination(destination: GeoLocation, label: String = "") {
        _destination.value = destination
        _searchQuery.value = label.ifBlank { _searchQuery.value }
        viewModelScope.launch {
            val origin = uiState.value.currentLocation ?: return@launch
            _route.value = computeRouteStub(origin, destination)
        }
    }

    fun clearRoute() {
        _destination.value = null
        _route.value = null
        _searchQuery.value = ""
    }

    /**
     * Straight-line "route" calculator. Distance is the great-circle distance;
     * duration assumes a flat 40 km/h cruise. Polyline contains only the two
     * endpoints so the map draws an A→B line. The shape of the [Route] data
     * class matches what a real Directions response would produce — the UI
     * doesn't change when routing goes live.
     */
    private fun computeRouteStub(origin: GeoLocation, destination: GeoLocation): Route {
        val distanceMeters = haversine(origin, destination).toInt()
        val cruiseMps = 40 * 1000 / 3600.0  // 40 km/h average
        val durationSec = (distanceMeters / cruiseMps).toInt()
        return Route(
            origin = origin,
            destination = destination,
            polyline = listOf(origin, destination),
            distanceMeters = distanceMeters,
            durationSeconds = durationSec
        )
    }

    private fun haversine(a: GeoLocation, b: GeoLocation): Double {
        val r = 6_371_000.0
        val dLat = (b.latitude - a.latitude) * PI / 180
        val dLng = (b.longitude - a.longitude) * PI / 180
        val s1 = sin(dLat / 2)
        val s2 = sin(dLng / 2)
        val hav = s1 * s1 + cos(a.latitude * PI / 180) * cos(b.latitude * PI / 180) * s2 * s2
        return 2 * r * atan2(sqrt(hav), sqrt(1 - hav))
    }
}

class MapsViewModelFactory(
    private val locationRepository: LocationRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapsViewModel::class.java)) {
            return MapsViewModel(locationRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
