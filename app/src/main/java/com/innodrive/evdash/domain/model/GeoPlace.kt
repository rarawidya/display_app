package com.innodrive.evdash.domain.model

/**
 * A geocoded place — a search result the user can pick as a destination.
 * Provider-neutral: any [com.innodrive.evdash.domain.repository.Geocoder] returns
 * these, so the search UI never depends on a geocoding SDK.
 *
 * @param name primary label (e.g. "Surabaya Gubeng Station")
 * @param detail secondary line (city / state / country, assembled by the adapter)
 * @param location the coordinate to route to
 */
data class GeoPlace(
    val name: String,
    val detail: String,
    val location: GeoLocation,
)
