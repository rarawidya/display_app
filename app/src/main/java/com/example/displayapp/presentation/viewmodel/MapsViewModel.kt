package com.example.displayapp.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.displayapp.data.navigation.NavigationCoordinator
import com.example.displayapp.data.energy.EfficiencyTracker
import com.example.displayapp.data.navigation.GeoMath
import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.GeoPlace
import com.example.displayapp.domain.model.NavProgress
import com.example.displayapp.domain.model.NavState
import com.example.displayapp.domain.model.Route
import com.example.displayapp.domain.model.VehicleData
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val vehicleData: StateFlow<VehicleData>,
    private val efficiency: StateFlow<EfficiencyTracker.State>,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    // Bumped on a location-permission grant. LocationRepository.location is a cold
    // flow that emits null and COMPLETES when permission is missing, so after a
    // grant it must be re-collected — flatMapLatest restarts it on each bump.
    private val _locationRestart = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveLocation = _locationRestart.flatMapLatest { locationRepository.location }

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
            else flow {
                // Dedupe by name+coords. The geocoder can return identical entries
                // (e.g. the same terminal indexed twice); duplicates both clutter the
                // list and collide the Navigation list's LazyColumn key — same key twice
                // is a hard crash. This identity matches SuggestionList's `key`, so the
                // rendered keys are unique by construction.
                emit(
                    geocoder.search(q, near = uiState.value.currentLocation)
                        .distinctBy { "${it.name}|${it.location.latitude},${it.location.longitude}" }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Name/address of the pending (previewed) destination, for the confirmation sheet.
    private val _pendingPlace = MutableStateFlow<GeoPlace?>(null)
    // Latched arrival: null = not arrived; non-null = arrived, value is the destination
    // label. Needed because the provider clears its active route the instant it reaches
    // NavState.Arrived (to end the BLE session), so arrival can't be derived from the
    // now-null active route — it's captured here and held until the user taps Done.
    private val _arrival = MutableStateFlow<String?>(null)
    // Bumps to request a fresh camera fit (new destination / Overview); pan doesn't.
    private val _fitToken = MutableStateFlow(0)
    // Temporary route Overview during active navigation (auto-reverts to follow).
    private val _overviewActive = MutableStateFlow(false)
    private var overviewJob: Job? = null

    init {
        // Capture the arrival the moment the tracker reports it, before the provider
        // tears the session down. Cleared when the user starts/cancels a route.
        coordinator.progress
            .onEach { p -> if (p?.state == NavState.Arrived) _arrival.value = p.destinationName }
            .launchIn(viewModelScope)
    }

    private data class RouteCore(
        val preview: RoutePlan?,
        val previewError: String?,
        val active: RoutePlan?,
        val fitToken: Int,
        val overview: Boolean,
    )

    private data class RouteState(
        val preview: RoutePlan?,
        val previewError: String?,
        val active: RoutePlan?,
        val fitToken: Int,
        val overview: Boolean,
        val progress: NavProgress?,
        val batteryPercent: Int,
        val rangeKm: Float?,
        val arrivedName: String?,
    )

    // Fold the live NavProgress + battery/range + latched arrival in with the route/
    // preview state so the ETA card can count down, show an estimated arrival charge, and
    // swap to the arrival card (combine's typed overload caps at 5 flows, hence nesting).
    private val routeStateFlow = combine(
        combine(
            coordinator.previewRoute, coordinator.previewError, coordinator.activeRoute,
            _fitToken, _overviewActive,
        ) { p, e, a, t, o -> RouteCore(p, e, a, t, o) },
        coordinator.progress,
        vehicleData,
        efficiency,
        _arrival,
    ) { core, prog, vd, eff, arrivedName ->
        RouteState(
            core.preview, core.previewError, core.active, core.fitToken, core.overview,
            prog, vd.batteryPercent, eff.rangeKm, arrivedName,
        )
    }

    val uiState: StateFlow<MapsUiState> = combine(
        liveLocation,
        _searchQuery,
        _pendingPlace,
        coordinator.previewDestination,
        routeStateFlow,
    ) { loc, query, place, pendingDest, rs ->
        val navigating = rs.active != null
        val previewing = pendingDest != null && !navigating
        // Draw the active route once navigating, otherwise the preview route. While
        // navigating, trim the already-travelled part so only the road ahead stays drawn
        // (Google-Maps style); the preview shows the whole route.
        val drawn = (rs.active ?: rs.preview)?.toRoute()?.let { route ->
            if (navigating && loc != null && route.polyline.size >= 2) {
                route.copy(polyline = GeoMath.remainingAhead(route.polyline, loc))
            } else route
        }
        MapsUiState(
            currentLocation = loc,
            searchQuery = query,
            destination = rs.active?.destination ?: pendingDest,
            route = drawn,
            previewing = previewing,
            previewPlanning = previewing && rs.preview == null && rs.previewError == null,
            previewFailed = previewing && rs.previewError != null,
            previewErrorMessage = rs.previewError?.takeIf { previewing },
            previewName = place?.name.orEmpty(),
            previewDetail = place?.detail.orEmpty(),
            navigating = navigating,
            overviewActive = navigating && rs.overview,
            fitToken = rs.fitToken,
            permissionGranted = loc != null,
            // Live countdown from the tracked progress while navigating; null otherwise
            // (labels then fall back to the route's static totals).
            remainingMeters = rs.progress?.distanceRemainingM?.takeIf { navigating },
            etaSeconds = rs.progress?.etaSeconds?.takeIf { navigating },
            // Battery: current SoC (null = disconnected) + estimated charge on arrival.
            batteryPercentNow = rs.batteryPercent.takeIf { it > 0 },
            batteryAtArrivalPct = estimateArrivalBattery(
                socNow = rs.batteryPercent,
                rangeKm = rs.rangeKm,
                remainingMeters = rs.progress?.distanceRemainingM?.takeIf { navigating }
                    ?: (rs.active ?: rs.preview)?.distanceMeters,
            ),
            // Arrival: swaps the ETA card for a "You've arrived" card. Latched (see
            // _arrival) because the active route is cleared the instant arrival fires.
            arrived = rs.arrivedName != null,
            destinationName = rs.arrivedName.orEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapsUiState(),
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** The user just granted location permission — restart the location stream. */
    fun onLocationPermissionGranted() {
        _locationRestart.value += 1
    }

    /** Pick a searched place → PREVIEW it (plan + show the confirmation sheet). */
    fun selectPlace(place: GeoPlace) {
        _searchQuery.value = ""
        _arrival.value = null // clear any prior arrival
        _pendingPlace.value = place
        _fitToken.value += 1 // frame the new route once
        coordinator.preview(place.location)
    }

    /**
     * Long-press the map → PREVIEW a dropped pin. Shows a placeholder name immediately,
     * then reverse-geocodes for the real address. Does NOT start navigation.
     */
    fun dropPin(location: GeoLocation) {
        _searchQuery.value = ""
        _arrival.value = null // clear any prior arrival
        _pendingPlace.value = GeoPlace(
            name = "Dropped pin",
            detail = "%.5f, %.5f".format(location.latitude, location.longitude),
            location = location,
        )
        _fitToken.value += 1 // frame the new route once
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
        // Snapshot the place label at confirm time (a dropped pin has usually been
        // reverse-geocoded to a real name by now) — it becomes the board's
        // RouteSummary.destinationName.
        coordinator.startPending(destinationName = _pendingPlace.value?.name.orEmpty())
        _pendingPlace.value = null
        _arrival.value = null
        _overviewActive.value = false
    }

    /**
     * Temporarily frame the whole route during navigation, then auto-return to follow.
     * Bumps the fit token so the map fits once; a timer reverts to follow mode.
     */
    fun overview() {
        if (!uiState.value.navigating) return
        _fitToken.value += 1
        _overviewActive.value = true
        overviewJob?.cancel()
        overviewJob = viewModelScope.launch {
            delay(OVERVIEW_REVERT_MS)
            _overviewActive.value = false
        }
    }

    /** Re-plan the previewed route after a failure (sheet's Retry action). */
    fun retryPreview() = coordinator.retryPreview()

    /** Back out of the confirmation without navigating. */
    fun dismissPreview() {
        coordinator.dismissPreview()
        _pendingPlace.value = null
    }

    fun clearRoute() {
        coordinator.cancel()
        overviewJob?.cancel()
        _overviewActive.value = false
        _pendingPlace.value = null
        _searchQuery.value = ""
        _arrival.value = null
    }

    private fun RoutePlan.toRoute() = Route(
        origin = origin,
        destination = destination,
        polyline = polyline,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
    )

    /**
     * Estimated battery % on arrival: the current charge minus the share of the current
     * range the remaining trip consumes. `rangeKm` is how far the present charge can go,
     * so `remaining / rangeKm` is the fraction of that charge the drive uses. Returns
     * null (→ falls back to current SoC / "—") until SoC, range, and a distance exist —
     * range needs a little driving history before `EfficiencyTracker` can estimate it.
     */
    private fun estimateArrivalBattery(socNow: Int, rangeKm: Float?, remainingMeters: Int?): Int? {
        if (socNow <= 0 || rangeKm == null || rangeKm <= 0f || remainingMeters == null) return null
        val remainingKm = remainingMeters / 1000f
        val arrival = socNow * (1f - remainingKm / rangeKm)
        return arrival.coerceIn(0f, socNow.toFloat()).roundToInt()
    }

    private companion object {
        const val OVERVIEW_REVERT_MS = 5_000L
    }
}

class MapsViewModelFactory(
    private val locationRepository: LocationRepository,
    private val coordinator: NavigationCoordinator,
    private val geocoder: Geocoder,
    private val vehicleData: StateFlow<VehicleData>,
    private val efficiency: StateFlow<EfficiencyTracker.State>,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapsViewModel::class.java)) {
            return MapsViewModel(locationRepository, coordinator, geocoder, vehicleData, efficiency) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
