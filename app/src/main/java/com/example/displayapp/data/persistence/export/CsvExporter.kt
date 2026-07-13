package com.example.displayapp.data.persistence.export

import android.content.Context
import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.dao.TripDao
import com.example.displayapp.data.persistence.entity.TripEntity
import com.example.displayapp.data.protocol.TelemetryDerivations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
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
 * Files are written to the app's external files directory (shareable via
 * Intent). Uses streaming write to handle large trips without loading
 * all data into memory.
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
                writeSummaryBlock(writer, trip, samples.size.toLong())
                writer.appendLine(CSV_HEADER)

                // Each row goes through the canonical entity→VehicleData decode
                // so rpm and power match what Drive/Charts/Trip Detail show for
                // the same wire frame. speed_kmh is formatted with one decimal
                // because the wire carries int10 precision (e.g. 45.2 km/h =
                // 452); the rest follow the wire precision contract documented
                // in TelemetryEntity.
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
                    // v7 wire status fields, persisted so the export matches the
                    // recorded frame (0 for pre-v7 rows).
                    writer.append(vd.faultCode.toString()).append(',')
                    writer.appendLine(vd.flags.toString())
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
