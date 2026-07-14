package com.innodrive.evdash.domain.model

/**
 * Future-feature placeholder for EV charging stations.
 *
 * The schema is intentionally minimal — extend with connector types, kW
 * rating, availability, etc. when wiring a real provider (OCPI, OpenChargeMap,
 * etc.). Kept in the domain layer so map markers can render off these without
 * coupling to any network model.
 */
data class ChargingStation(
    val id: String,
    val name: String,
    val location: GeoLocation,
    val available: Boolean = true
)
