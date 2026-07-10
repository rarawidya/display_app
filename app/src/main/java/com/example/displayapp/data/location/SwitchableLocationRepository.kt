package com.example.displayapp.data.location

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.repository.LocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * A [LocationRepository] with a **stable identity** that delegates to a swappable
 * underlying source (real fused GPS ↔ [SimulatedLocationRepository]).
 *
 * Mirrors [com.example.displayapp.data.bluetooth.SwitchableDataSource]: the navigation
 * provider, coordinator, and `MapsViewModel` capture the location repository once, so if
 * the container replaced its field the captured collectors would keep reading the old
 * source. This facade fixes that — [location] re-collects the active delegate on every
 * [swap], so flipping the Developer simulator toggle moves the map/nav onto (or off of)
 * the route-walking source without re-wiring any consumer.
 */
class SwitchableLocationRepository(
    initial: LocationRepository
) : LocationRepository {

    private val _delegate = MutableStateFlow(initial)

    /** Current delegate (for the container to decide whether a swap is needed). */
    val active: LocationRepository get() = _delegate.value

    @OptIn(ExperimentalCoroutinesApi::class)
    override val location: Flow<GeoLocation?> = _delegate.flatMapLatest { it.location }

    /** Re-point to [next]; active collectors switch to its fixes on the next emission. */
    fun swap(next: LocationRepository) {
        _delegate.value = next
    }
}
