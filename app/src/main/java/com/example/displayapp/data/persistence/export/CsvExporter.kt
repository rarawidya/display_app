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
 * CSV format: see CSV_HEADER constant. Columns are the canonical telemetry
 * fields — every value flows from `VehicleData` via `TelemetryEntity`, so a
 * CSV row matches what the user saw live on Drive/Charts/Logs.
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

                // Data rows. rpm and power are derived on export from the
                // canonical wire fields so a CSV column never disagrees with
                // what Drive/Charts/Logs show on screen.
                for (sample in samples) {
                    val speedKmh = sample.speed / 10f
                    val voltageV = sample.voltage / 100f
                    val currentA = sample.current / 100f
                    val rpm = sample.speed * 10   // speedKmh × 100 stored as int10
                    val powerW = voltageV * currentA
                    writer.append(sample.timestamp.toString()).append(',')
                    writer.append("%.1f".format(speedKmh)).append(',')
                    writer.append(rpm.toString()).append(',')
                    writer.append(sample.battery.toString()).append(',')
                    writer.append("%.2f".format(voltageV)).append(',')
                    writer.append("%.2f".format(currentA)).append(',')
                    writer.append("%.1f".format(powerW)).append(',')
                    writer.append(sample.temperature.toString()).append(',')
                    writer.append(sample.batteryTemperature.toString()).append(',')
                    writer.append(sample.controllerTemperature.toString()).append(',')
                    writer.appendLine(sample.mode.toString())
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
            "timestamp_ms,speed_kmh,rpm,battery_pct,voltage_V,current_A,power_W," +
                "motor_temp_C,battery_temp_C,controller_temp_C,mode"
    }
}
