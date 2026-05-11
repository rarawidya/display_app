package com.example.displayapp.data.persistence.export

import android.content.Context
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports trip telemetry data to CSV files.
 *
 * CSV format:
 *   timestamp,speed_kmh,battery_%,voltage_V,current_A,temp_C,odometer_km,mode,indicators
 *
 * Files are written to the app's external files directory (shareable via Intent).
 * Uses streaming write to handle large trips without loading all data into memory.
 */
class CsvExporter(
    private val context: Context,
    private val telemetryDao: TelemetryDao,
    private val tripDao: TripDao
) {

    /**
     * Exports a trip's telemetry to a CSV file.
     * Returns the File if successful, null on failure.
     */
    suspend fun exportTrip(tripId: Long): File? = withContext(Dispatchers.IO) {
        val trip = tripDao.getById(tripId) ?: run {
            Timber.e("Trip $tripId not found")
            return@withContext null
        }

        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "ev_trip_${dateFormat.format(Date(trip.startTime))}.csv"
        val exportDir = File(context.getExternalFilesDir(null), "exports")
        exportDir.mkdirs()
        val file = File(exportDir, fileName)

        try {
            val samples = telemetryDao.getByTrip(tripId)
            Timber.i("Exporting trip $tripId: ${samples.size} samples to $fileName")

            file.bufferedWriter().use { writer ->
                // Header
                writer.appendLine(CSV_HEADER)

                // Data rows
                for (sample in samples) {
                    writer.append(sample.timestamp.toString()).append(',')
                    writer.append("%.1f".format(sample.speed / 10f)).append(',')
                    writer.append(sample.battery.toString()).append(',')
                    writer.append("%.2f".format(sample.voltage / 100f)).append(',')
                    writer.append("%.2f".format(sample.current / 100f)).append(',')
                    writer.append(sample.temperature.toString()).append(',')
                    writer.append("%.3f".format(sample.odometer / 1000f)).append(',')
                    writer.append(sample.mode.toString()).append(',')
                    writer.appendLine(sample.indicators.toString())
                }
            }

            Timber.i("Export complete: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Timber.e(e, "CSV export failed for trip $tripId")
            file.delete()
            null
        }
    }

    /**
     * Returns a summary line for the trip (useful for share intents).
     */
    suspend fun getTripSummary(tripId: Long): String? {
        val trip = tripDao.getById(tripId) ?: return null
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val duration = ((trip.endTime ?: System.currentTimeMillis()) - trip.startTime) / 1000
        val distKm = trip.distanceMeters / 1000f
        val maxSpd = trip.maxSpeedKmh10 / 10f

        return buildString {
            appendLine("EV Trip Summary")
            appendLine("Date: ${dateFormat.format(Date(trip.startTime))}")
            appendLine("Duration: ${duration / 60}m ${duration % 60}s")
            appendLine("Distance: ${"%.1f".format(distKm)} km")
            appendLine("Max Speed: ${"%.1f".format(maxSpd)} km/h")
            appendLine("Battery: ${trip.startBattery}% → ${trip.endBattery ?: "?"}%")
            appendLine("Samples: ${trip.sampleCount}")
        }
    }

    companion object {
        private const val CSV_HEADER =
            "timestamp_ms,speed_kmh,battery_pct,voltage_V,current_A,temperature_C,odometer_km,mode,indicators"
    }
}
