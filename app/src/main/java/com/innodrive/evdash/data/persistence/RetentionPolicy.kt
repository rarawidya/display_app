package com.innodrive.evdash.data.persistence

import com.innodrive.evdash.data.persistence.dao.FaultEventDao
import com.innodrive.evdash.data.persistence.dao.TelemetryDao
import com.innodrive.evdash.data.persistence.dao.TripDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages telemetry data retention to prevent unbounded database growth.
 *
 * Default retention: 30 days for telemetry, 90 days for trips/faults.
 *
 * Storage budget estimation:
 * - 1 hour of 20Hz recording ≈ 72,000 rows ≈ 3 MB
 * - 2 hours/day × 30 days = 60 hours ≈ 180 MB
 * - This is acceptable for modern mobile devices
 *
 * Call [enforce] periodically (e.g., on app start, on trip end)
 * to prune old data and keep the database lean.
 */
class RetentionPolicy(
    private val telemetryDao: TelemetryDao,
    private val tripDao: TripDao,
    private val faultEventDao: FaultEventDao,
    private val telemetryRetentionDays: Int = 30,
    private val tripRetentionDays: Int = 90,
    private val faultRetentionDays: Int = 90
) {

    /**
     * Deletes data older than the configured retention periods.
     * Returns a summary of what was deleted.
     *
     * @param overrideDays if non-null, overrides the configured retention for
     * all categories (used by the Settings → Retention preference). A value of
     * `-1` means "keep forever" and skips the prune entirely.
     */
    suspend fun enforce(overrideDays: Int? = null): RetentionResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // "Forever" preference short-circuits the prune so no rows are touched.
        if (overrideDays != null && overrideDays < 0) {
            return@withContext RetentionResult(0, 0, 0)
        }

        val telemetryDays = overrideDays ?: telemetryRetentionDays
        val tripDays = overrideDays ?: tripRetentionDays
        val faultDays = overrideDays ?: faultRetentionDays

        val telemetryCutoff = now - telemetryDays * DAY_MS
        val tripCutoff = now - tripDays * DAY_MS
        val faultCutoff = now - faultDays * DAY_MS

        val telemetryDeleted = telemetryDao.deleteOlderThan(telemetryCutoff)
        val tripsDeleted = tripDao.deleteOlderThan(tripCutoff)
        val faultsDeleted = faultEventDao.deleteOlderThan(faultCutoff)

        val result = RetentionResult(telemetryDeleted, tripsDeleted, faultsDeleted)

        if (result.totalDeleted > 0) {
            Timber.i("Retention cleanup: $result")
        }

        result
    }

    /**
     * Returns current database stats for diagnostics.
     */
    suspend fun getStats(): StorageStats = withContext(Dispatchers.IO) {
        val oldestTimestamp = telemetryDao.getOldestTimestamp()
        val oldestAge = if (oldestTimestamp != null) {
            (System.currentTimeMillis() - oldestTimestamp) / DAY_MS
        } else 0L

        StorageStats(
            oldestDataDays = oldestAge.toInt(),
            retentionDays = telemetryRetentionDays
        )
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

data class RetentionResult(
    val telemetryRowsDeleted: Int,
    val tripsDeleted: Int,
    val faultsDeleted: Int
) {
    val totalDeleted: Int get() = telemetryRowsDeleted + tripsDeleted + faultsDeleted

    override fun toString(): String =
        "telemetry=$telemetryRowsDeleted, trips=$tripsDeleted, faults=$faultsDeleted"
}

data class StorageStats(
    val oldestDataDays: Int,
    val retentionDays: Int
)
