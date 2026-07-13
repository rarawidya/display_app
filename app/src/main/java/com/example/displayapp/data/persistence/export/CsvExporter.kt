package com.example.displayapp.data.persistence.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.data.protocol.TelemetryDerivations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports trip telemetry data to CSV files.
 *
 * File shape:
 *   1. A commented summary header block with the trip aggregates the UI
 *      shows (Trip Detail card values), so an offline analyst gets the same
 *      headline numbers without scanning every row. Every line starts with
 *      `#` so RFC-4180 readers / `pandas.read_csv(comment='#')` skip it.
 *   2. The canonical per-sample row header.
 *   3. One row per persisted sample. Every value flows from
 *      [TelemetryDerivations.decodeEntity] — the same path replay /
 *      Trip Detail use — so a CSV column never disagrees with what the
 *      user saw live on Drive / Charts / Logs.
 *
 * [exportTripToDownloads] writes to the phone's public **Downloads** folder
 * (MediaStore on API 29+, app-files fallback below) so the CSV is a real,
 * user-visible file. Streaming write handles large trips without loading all
 * data into memory.
 */
class CsvExporter(
    private val context: Context,
    private val telemetryDao: TelemetryDao,
    private val tripDao: TripDao
) {

    /** A saved CSV: its user-visible file name + a URI to open or share it. */
    data class CsvExportResult(val displayName: String, val uri: Uri)

    /**
     * Export a trip's telemetry to the phone's public **Downloads** folder so it
     * shows up as a real, user-visible file (not app-private storage the user
     * can't browse to). Uses MediaStore on API 29+ (no storage permission); on
     * older devices it falls back to the app's external files dir + a
     * FileProvider URI (still openable/shareable, just not in public Downloads).
     * Returns the file name + a URI, or null on failure.
     */
    suspend fun exportTripToDownloads(tripId: Long): CsvExportResult? = withContext(Dispatchers.IO) {
        val trip = tripDao.getById(tripId) ?: run {
            Timber.e("Trip $tripId not found")
            return@withContext null
        }
        val samples = telemetryDao.getByTrip(tripId)
        val fileName = "ev_trip_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(trip.startTime))}.csv"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsMediaStore(fileName, trip, samples)
            } else {
                saveToAppFiles(fileName, trip, samples)
            }
        } catch (e: Exception) {
            Timber.e(e, "CSV download export failed for trip $tripId")
            null
        }
    }

    /** API 29+: insert into MediaStore Downloads and stream the CSV into it. */
    private fun saveToDownloadsMediaStore(
        fileName: String,
        trip: TripEntity,
        samples: List<TelemetryEntity>,
    ): CsvExportResult? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // IS_PENDING hides the row until we finish writing, so a reader never
            // sees a half-written file.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { writeCsv(it, trip, samples) }
                ?: throw IOException("null output stream for $uri")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Timber.i("Exported trip ${trip.id} to Downloads/$fileName")
            CsvExportResult(fileName, uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a pending stub
            throw e
        }
    }

    /** API < 29 fallback: app external files + a FileProvider URI (no permission). */
    private fun saveToAppFiles(
        fileName: String,
        trip: TripEntity,
        samples: List<TelemetryEntity>,
    ): CsvExportResult {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.bufferedWriter().use { writeCsv(it, trip, samples) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return CsvExportResult(fileName, uri)
    }

    /**
     * Writes the full CSV (summary block + header + one row per sample) to
     * [writer]. Single source of the file shape, shared by every export target.
     * Every value flows through [TelemetryDerivations.decodeEntity] so a column
     * never disagrees with what the user saw live on Drive / Charts / Trip Detail.
     */
    private fun writeCsv(writer: BufferedWriter, trip: TripEntity, samples: List<TelemetryEntity>) {
        writeSummaryBlock(writer, trip, samples.size.toLong())
        writer.appendLine(CSV_HEADER)
        for (sample in samples) {
            val vd = TelemetryDerivations.decodeEntity(sample)
            val speedKmh = sample.speed / 10f
            // Pin Locale.US on every float: a comma-decimal locale (id-ID, de-DE,
            // …) would emit "45,2" and split one field into two, shifting every
            // column and corrupting the machine-readable CSV.
            writer.append(sample.timestamp.toString()).append(',')
            writer.append("%.1f".format(Locale.US, speedKmh)).append(',')
            writer.append(vd.rpm.toString()).append(',')
            writer.append(vd.batteryPercent.toString()).append(',')
            writer.append("%.2f".format(Locale.US, vd.voltage)).append(',')
            writer.append("%.2f".format(Locale.US, vd.current)).append(',')
            writer.append("%.1f".format(Locale.US, vd.power)).append(',')
            writer.append(vd.temperature.toString()).append(',')
            writer.append(vd.batteryTemperature.toString()).append(',')
            writer.append(vd.controllerTemperature.toString()).append(',')
            writer.append(sample.mode.toString()).append(',')
            // v7 wire status fields, persisted so the export matches the recorded
            // frame (0 for pre-v7 rows).
            writer.append(vd.faultCode.toString()).append(',')
            writer.appendLine(vd.flags.toString())
        }
    }

    /**
     * Writes the leading `#`-prefixed summary block. Mirrors every aggregate
     * Trip Detail surfaces (start/end time, distance, duration, energy,
     * avg/max power, peak temps, battery delta, sample count) so an offline
     * consumer doesn't need to re-aggregate the per-sample rows. Pre-v5
     * trips render `—` for aggregates that didn't exist in their schema.
     *
     * Also pins the sign-convention contract in the file itself: analysts
     * scripting against the CSV shouldn't have to guess which direction
     * "positive current" means.
     */
    private fun writeSummaryBlock(writer: BufferedWriter, trip: TripEntity, sampleCount: Long) {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val durationSec = ((trip.endTime ?: System.currentTimeMillis()) - trip.startTime) / 1000L
        val distanceKm = trip.distanceMeters / 1000.0
        val avgSpeedKmh = trip.avgSpeedKmh10 / 10f
        val maxSpeedKmh = trip.maxSpeedKmh10 / 10f
        val avgPowerW = trip.avgPowerW100 / 100f
        val maxPowerW = trip.maxPowerW100 / 100f
        val netEnergyWh = trip.energyUsedWh - trip.energyRegenWh

        writer.appendLine("# EV Trip Export — DisplayApp")
        writer.appendLine("# trip_id=${trip.id}")
        writer.appendLine("# start=${isoFormat.format(Date(trip.startTime))}")
        writer.appendLine("# end=${trip.endTime?.let { isoFormat.format(Date(it)) } ?: "—"}")
        writer.appendLine("# duration_sec=$durationSec")
        writer.appendLine("# samples=$sampleCount")
        writer.appendLine("# distance_km=${"%.3f".format(Locale.US, distanceKm)}")
        writer.appendLine("# avg_speed_kmh=${formatOptional(avgSpeedKmh)}")
        writer.appendLine("# max_speed_kmh=${formatOptional(maxSpeedKmh)}")
        writer.appendLine("# battery_start_pct=${trip.startBattery}")
        writer.appendLine("# battery_end_pct=${trip.endBattery ?: "—"}")
        writer.appendLine("# energy_used_wh=${"%.2f".format(Locale.US, trip.energyUsedWh)}")
        writer.appendLine("# energy_regen_wh=${"%.2f".format(Locale.US, trip.energyRegenWh)}")
        writer.appendLine("# energy_net_wh=${"%.2f".format(Locale.US, netEnergyWh)}")
        writer.appendLine("# avg_power_w=${formatOptional(avgPowerW)}")
        writer.appendLine("# max_power_w=${formatOptional(maxPowerW)}")
        writer.appendLine("# peak_motor_temp_c=${peakTempLabel(trip.peakMotorTempC)}")
        writer.appendLine("# peak_battery_temp_c=${peakTempLabel(trip.peakBatteryTempC)}")
        writer.appendLine("# peak_controller_temp_c=${peakTempLabel(trip.peakControllerTempC)}")
        writer.appendLine("# sign_convention=current>0 discharge, current<0 regen; power = voltage*current")
        writer.appendLine("# rpm_semantics=display proxy (speed_kmh*100), not motor electrical frequency")
        writer.appendLine("# timestamp_semantics=wall-clock epoch millis at decode (TelemetryMapper)")
        writer.appendLine("# schema=telemetry.capnp; see CLAUDE.md 'Canonical telemetry invariants'")
    }

    /** Renders `0` aggregates from pre-v5 trips as `—` instead of fake zeros. */
    private fun formatOptional(value: Float): String =
        if (value == 0f) "—" else "%.2f".format(Locale.US, value)

    /** Same for peak temps (pre-v5 default 0 → "—"). */
    private fun peakTempLabel(value: Int): String =
        if (value == 0) "—" else value.toString()

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
                "motor_temp_C,battery_temp_C,controller_temp_C,mode,fault_code,flags"
    }
}
