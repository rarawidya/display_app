package com.example.displayapp.data.persistence

import android.content.Context
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Snapshot of on-device data usage shown in the Settings → Storage section.
 */
data class StorageInfo(
    val databaseBytes: Long,
    val tripCount: Int,
    val telemetrySampleCount: Long,
    val exportsBytes: Long
) {
    val totalBytes: Long get() = databaseBytes + exportsBytes

    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024        -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L               -> "%.0f KB".format(bytes / 1024.0)
        else                          -> "$bytes B"
    }
}

/**
 * Reads on-disk database size + counts.
 *
 * Lives in the data layer so the presentation layer can ask for one
 * [StorageInfo] snapshot rather than juggling File I/O + DAO calls itself.
 *
 * Note on size accuracy: Room writes through SQLite WAL, so the .db file size
 * may understate "real" storage. We also report the -wal and -shm sidecars
 * if present.
 */
class StorageInfoProvider(
    private val context: Context,
    private val tripDao: TripDao,
    private val telemetryDao: TelemetryDao
) {

    suspend fun snapshot(): StorageInfo = withContext(Dispatchers.IO) {
        val dbBase = context.getDatabasePath("ev_telemetry.db")
        val dbSize = filesSize(dbBase, File(dbBase.path + "-wal"), File(dbBase.path + "-shm"))

        val tripCount = tripDao.getAll().size
        val sampleCount = telemetryDao.getOldestTimestamp()?.let {
            // We don't have a COUNT query exposed; sum tripDao sampleCounts.
            tripDao.getAll().sumOf { trip -> trip.sampleCount }
        } ?: 0L

        val exportsDir = File(context.getExternalFilesDir(null), "exports")
        val exportsSize = if (exportsDir.exists()) {
            exportsDir.walk().filter { it.isFile }.sumOf { it.length() }
        } else 0L

        StorageInfo(
            databaseBytes = dbSize,
            tripCount = tripCount,
            telemetrySampleCount = sampleCount,
            exportsBytes = exportsSize
        )
    }

    /**
     * Hard-deletes every trip (and via foreign-key CASCADE, every telemetry sample).
     * Telemetry that somehow got orphaned (shouldn't happen, but defensive) is
     * cleared via a far-future cutoff.
     */
    suspend fun clearAllTripsAndTelemetry() = withContext(Dispatchers.IO) {
        // delete trips first → CASCADE clears telemetry
        tripDao.getAll().forEach { tripDao.deleteById(it.id) }
        // sweep any orphans
        telemetryDao.deleteOlderThan(Long.MAX_VALUE)
    }

    /**
     * Removes everything in the exports directory (CSV files only).
     * Doesn't touch the database.
     */
    suspend fun clearExportsCache() = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "exports")
        if (dir.exists()) dir.walk().filter { it.isFile }.forEach { it.delete() }
    }

    private fun filesSize(vararg files: File): Long =
        files.sumOf { if (it.exists()) it.length() else 0L }
}
