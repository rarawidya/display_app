package com.innodrive.evdash.data.simulator

import com.innodrive.evdash.data.fault.FaultDetector
import com.innodrive.evdash.data.persistence.dao.FaultEventDao
import com.innodrive.evdash.data.persistence.dao.TelemetryDao
import com.innodrive.evdash.data.persistence.dao.TripDao
import com.innodrive.evdash.data.persistence.entity.FaultEventEntity
import com.innodrive.evdash.data.persistence.entity.TelemetryEntity
import com.innodrive.evdash.data.persistence.entity.TripEntity
import com.innodrive.evdash.data.protocol.TelemetryDerivations
import kotlin.math.roundToInt
import kotlin.math.sin
import timber.log.Timber

/**
 * Populates the trips table with a handful of deterministic demo trips the
 * first time the user opens the app while [com.innodrive.evdash.di.AppContainer.useSimulator]
 * is on.
 *
 * The per-sample telemetry is generated with the **exact** waveforms that
 * [com.innodrive.evdash.data.simulator.ScenarioGenerator.cityCruise]
 * emits to the Drive page in real time, so the Speed / Voltage / Current /
 * Temperature charts on the Trip Detail screen draw the same curves a user
 * sees live on the Drive page. Every aggregate stored on [TripEntity]
 * (`distanceMeters`, `maxSpeedKmh10`, `avgSpeedKmh10`, `energyUsedWh`,
 * `energyRegenWh`) is recomputed from the generated samples, so the headline
 * cards in Logs stay internally consistent with the charts.
 *
 * Idempotent: bails out if any trip rows already exist, so user deletes stick.
 */
class SampleTripSeeder(
    private val tripDao: TripDao,
    private val telemetryDao: TelemetryDao,
    private val faultEventDao: FaultEventDao,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun seedIfEmpty() {
        if (tripDao.getAll().isNotEmpty()) return

        val nowMs = now()

        DEMOS.forEach { d ->
            val endMs = nowMs - d.endedAgoMs
            val startMs = endMs - d.durationMs
            val (samples, agg) = generateTelemetry(
                tripId = 0L, // patched after insert
                startMs = startMs,
                durationSec = (d.durationMs / 1000L).toInt().coerceAtLeast(1),
                startBattery = d.startBattery,
                endBattery = d.endBattery
            )
            val trip = TripEntity(
                startTime = startMs,
                endTime = endMs,
                distanceMeters = agg.distanceMeters,
                maxSpeedKmh10 = agg.maxSpeedKmh10,
                avgSpeedKmh10 = agg.avgSpeedKmh10,
                startBattery = d.startBattery,
                endBattery = d.endBattery,
                sampleCount = samples.size.toLong(),
                energyUsedWh = agg.energyUsedWh,
                energyRegenWh = agg.energyRegenWh,
                avgPowerW100 = agg.avgPowerW100,
                maxPowerW100 = agg.maxPowerW100,
                peakMotorTempC = agg.peakMotorTempC,
                peakBatteryTempC = agg.peakBatteryTempC,
                peakControllerTempC = agg.peakControllerTempC
            )
            val tripId = tripDao.insert(trip)
            telemetryDao.insertBatch(samples.map { it.copy(tripId = tripId) })
            // A few demo trips carry illustrative fault rows so the Trip Detail
            // "Faults" section has something to render; the rest stay clean.
            d.faults.forEach { f ->
                faultEventDao.insert(
                    FaultEventEntity(
                        timestamp = startMs + f.offsetMs,
                        tripId = tripId,
                        type = f.type,
                        severity = f.severity,
                        message = f.message
                    )
                )
            }
        }
        Timber.tag("SampleTripSeeder").i("Seeded ${DEMOS.size} demo trips")
    }

    private data class Aggregates(
        val distanceMeters: Long,
        val maxSpeedKmh10: Int,
        val avgSpeedKmh10: Int,
        val energyUsedWh: Double,
        val energyRegenWh: Double,
        val avgPowerW100: Int,
        val maxPowerW100: Int,
        val peakMotorTempC: Int,
        val peakBatteryTempC: Int,
        val peakControllerTempC: Int
    )

    /**
     * Generates 1 Hz telemetry using cityCruise's waveforms verbatim:
     *
     *   speed     = 30 + 15·sin(t·0.2)     km/h   → 15..45, mean 30, peak 45
     *   voltage   = 72 + 2·sin(t·0.1)      V      → 70..74
     *   current   = 8 + 4·sin(t·0.3)       A      → 4..12
     *   temperature = 38 + 3·sin(t·0.05)   °C     → 35..41
     *
     * Battery is interpolated linearly from `startBattery` to `endBattery`
     * across the trip so the headline `startBattery → endBattery` label on
     * the trip card always lines up with the per-sample battery chart.
     *
     * The simulator sends frames at 20 Hz where `t = tick * 0.05`, so `t`
     * advances 1.0 per real-time second — identical to this 1 Hz seeder
     * incrementing `t = sec`. Same effective rate, same visible curve.
     */
    private fun generateTelemetry(
        tripId: Long,
        startMs: Long,
        durationSec: Int,
        startBattery: Int,
        endBattery: Int
    ): Pair<List<TelemetryEntity>, Aggregates> {
        val samples = ArrayList<TelemetryEntity>(durationSec)
        var distanceMeters = 0.0
        var speedSum10 = 0L
        var maxSpeed10 = 0
        var energyUsedWh = 0.0
        var energyRegenWh = 0.0
        var powerW100Sum = 0L
        var maxPowerW100 = 0
        var peakMotorTemp = Int.MIN_VALUE
        var peakBatteryTemp = Int.MIN_VALUE
        var peakControllerTemp = Int.MIN_VALUE

        for (i in 0 until durationSec) {
            val t = i.toDouble()
            val speedKmh = 30.0 + 15.0 * sin(t * 0.2)
            val voltageV = 72.0 + 2.0 * sin(t * 0.1)
            val currentA = 8.0 + 4.0 * sin(t * 0.3)
            val tempC = 38.0 + 3.0 * sin(t * 0.05)

            val frac = if (durationSec > 1) i.toDouble() / (durationSec - 1) else 1.0
            val batteryPct = (startBattery - (startBattery - endBattery) * frac)
                .coerceIn(0.0, 100.0)

            // Distance from speed × dt at 1 Hz. Becomes the trip aggregate.
            distanceMeters += speedKmh / 3.6

            // Aggregates for the trip headline.
            val speed10 = (speedKmh * 10).roundToInt()
            speedSum10 += speed10
            if (speed10 > maxSpeed10) maxSpeed10 = speed10

            // Energy: V × I × dt, split by sign. cityCruise's current is
            // strictly positive so regen stays at zero — matching what the
            // live Drive page would integrate.
            val watt = voltageV * currentA
            if (watt >= 0) energyUsedWh += watt / 3600.0
            else energyRegenWh += -watt / 3600.0

            // v5 aggregates — power × 100 (centi-watts), peak temps.
            val powerW100 = (watt * 100).toInt()
            powerW100Sum += powerW100
            if (powerW100 > maxPowerW100) maxPowerW100 = powerW100
            val motorTempInt = tempC.roundToInt()
            val battTempInt = (tempC - 12).roundToInt()
            val ctrlTempInt = (tempC - 7).roundToInt()
            if (motorTempInt > peakMotorTemp) peakMotorTemp = motorTempInt
            if (battTempInt > peakBatteryTemp) peakBatteryTemp = battTempInt
            if (ctrlTempInt > peakControllerTemp) peakControllerTemp = ctrlTempInt

            // v6: persist the canonical derivations on the seeded rows too,
            // so the seeder is a literal example of "what live decode would
            // have written for this wire frame". TelemetryDerivations is the
            // ONLY place these formulas live.
            val speedKmhInt = speed10 / 10
            samples.add(
                TelemetryEntity(
                    tripId = tripId,
                    timestamp = startMs + i * 1000L,
                    speed = speed10,
                    battery = batteryPct.roundToInt(),
                    voltage = (voltageV * 100).roundToInt(),
                    current = (currentA * 100).roundToInt(),
                    temperature = motorTempInt,
                    mode = 2, // cityCruise emits NORMAL
                    // Same default relationship the simulator uses on the wire.
                    batteryTemperature = battTempInt,
                    controllerTemperature = ctrlTempInt,
                    rpm = TelemetryDerivations.rpmFromSpeedKmh(speedKmhInt),
                    powerW = TelemetryDerivations.powerFromVoltsAmps(voltageV.toFloat(), currentA.toFloat())
                )
            )
        }

        val agg = Aggregates(
            distanceMeters = distanceMeters.toLong(),
            maxSpeedKmh10 = maxSpeed10,
            avgSpeedKmh10 = (speedSum10 / durationSec).toInt(),
            energyUsedWh = energyUsedWh,
            energyRegenWh = energyRegenWh,
            avgPowerW100 = (powerW100Sum / durationSec).toInt(),
            maxPowerW100 = maxPowerW100,
            peakMotorTempC = peakMotorTemp.coerceAtLeast(0),
            peakBatteryTempC = peakBatteryTemp.coerceAtLeast(0),
            peakControllerTempC = peakControllerTemp.coerceAtLeast(0)
        )
        return samples to agg
    }

    private data class SeedFault(
        val offsetMs: Long,
        val type: String,
        val severity: Int,
        val message: String
    )

    private data class Demo(
        val endedAgoMs: Long,
        val durationMs: Long,
        val startBattery: Int,
        val endBattery: Int,
        val faults: List<SeedFault> = emptyList()
    )

    private companion object {
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR

        // Only inputs the formula actually needs: duration, when the trip
        // ended, and the start/end SoC visible on the card. Every other
        // displayed stat is derived from cityCruise's waveforms so the
        // numbers in Logs / Trip Detail line up with what Drive emits.
        // Ordered newest → oldest.
        private val DEMOS = listOf(
            Demo(endedAgoMs = 2 * HOUR,           durationMs = 18 * MIN, startBattery = 78, endBattery = 66),
            Demo(endedAgoMs = 1 * DAY + 3 * HOUR, durationMs = 9 * MIN,  startBattery = 71, endBattery = 65),
            Demo(
                endedAgoMs = 2 * DAY + 5 * HOUR, durationMs = 47 * MIN,
                startBattery = 85, endBattery = 55,
                faults = listOf(
                    SeedFault(
                        offsetMs = 16 * MIN,
                        type = FaultDetector.TYPE_MOTOR_TEMP,
                        severity = FaultDetector.SEVERITY_WARNING,
                        message = "Motor temperature high (54°C)"
                    ),
                    SeedFault(
                        offsetMs = 31 * MIN,
                        type = FaultDetector.TYPE_CONTROLLER_TEMP,
                        severity = FaultDetector.SEVERITY_CRITICAL,
                        message = "Controller temperature critical (72°C)"
                    )
                )
            ),
            Demo(endedAgoMs = 3 * DAY + 8 * HOUR, durationMs = 14 * MIN, startBattery = 60, endBattery = 51),
            Demo(endedAgoMs = 5 * DAY + 2 * HOUR, durationMs = 33 * MIN, startBattery = 82, endBattery = 61),
            Demo(endedAgoMs = 7 * DAY + 6 * HOUR, durationMs = 6 * MIN,  startBattery = 70, endBattery = 66)
        )
    }
}
