package com.example.displayapp.domain.repository

import com.example.displayapp.domain.model.GeoLocation
import kotlinx.coroutines.flow.Flow

/**
 * Streams the device's current GPS fix.
 *
 * Emits `null` when no fix is available *yet* or when the runtime permission
 * is missing — the UI shows a fallback in those cases. Once a fix arrives,
 * non-null values stream in at the cadence the provider chooses.
 */
interface LocationRepository {
    val location: Flow<GeoLocation?>
}
