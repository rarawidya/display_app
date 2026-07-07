package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.GeoLocation
import com.example.displayapp.domain.model.GeoPlace

/**
 * Provider port for **place search** (text → coordinates). Separate from routing so the
 * geocoding SDK is swappable: `GraphHopperGeocoder` today, ORS/MapTiler/Nominatim later.
 * The search UI depends only on this port + [GeoPlace].
 */
interface Geocoder {

    /** True when usable (an API key/endpoint is configured). */
    val isConfigured: Boolean

    /**
     * Search for places matching [query], optionally biased toward [near] (the current
     * location). Returns an ordered list of candidates, or empty when unconfigured,
     * blank, or on error.
     */
    suspend fun search(query: String, near: GeoLocation? = null): List<GeoPlace>

    /**
     * Reverse-geocode a coordinate to a named place (for a dropped pin), or null when
     * unconfigured / nothing found. Default returns null for geocoders without reverse.
     */
    suspend fun reverse(location: GeoLocation): GeoPlace? = null
}
