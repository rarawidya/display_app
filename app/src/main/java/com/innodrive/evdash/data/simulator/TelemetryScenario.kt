package com.innodrive.evdash.data.simulator

import kotlin.math.sin
import kotlin.random.Random

/**
 * Predefined telemetry scenarios for testing different UI states
 * without real hardware. Each scenario generates values that exercise
 * specific visual behaviors (alerts, animations, edge cases).
 */
enum class TelemetryScenario {
    /** Normal city driving with moderate values */
    CITY_CRUISE,
    /** Highway speed with high current draw */
    HIGHWAY,
    /** Low battery warning scenario */
    LOW_BATTERY,
    /** High temperature critical alert */
    OVERHEATING,
    /** Regenerative braking (negative current) */
    REGEN_BRAKING,
    /** Stationary / parked */
    PARKED,
    /** Aggressive acceleration and mode changes */
    SPORT_MODE,
    /** Linear speed sweep 0→180→0 km/h — drives the gauge across its full scale. */
    GAUGE_SWEEP
}

/**
 * Generates telemetry values for a given scenario at a specific time tick.
 */
class ScenarioGenerator(private val scenario: TelemetryScenario) {

    fun generate(tick: Long): ScenarioFrame {
        val t = tick * 0.05 // time in seconds-ish
        return when (scenario) {
            TelemetryScenario.CITY_CRUISE -> cityCruise(t, tick)
            TelemetryScenario.HIGHWAY -> highway(t, tick)
            TelemetryScenario.LOW_BATTERY -> lowBattery(t, tick)
            TelemetryScenario.OVERHEATING -> overheating(t, tick)
            TelemetryScenario.REGEN_BRAKING -> regenBraking(t, tick)
            TelemetryScenario.PARKED -> parked(tick)
            TelemetryScenario.SPORT_MODE -> sportMode(t, tick)
            TelemetryScenario.GAUGE_SWEEP -> gaugeSweep(t)
        }
    }

    private fun cityCruise(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = 30.0 + 15.0 * sin(t * 0.2),
        battery = (75 - tick * 0.005).toInt().coerceIn(20, 100),
        voltageV = 72.0 + 2.0 * sin(t * 0.1),
        currentA = 8.0 + 4.0 * sin(t * 0.3),
        tempC = 38 + (3 * sin(t * 0.05)).toInt(),
        mode = 2, // NORMAL
    )

    private fun highway(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = 80.0 + 20.0 * sin(t * 0.1),
        battery = (60 - tick * 0.01).toInt().coerceIn(10, 100),
        voltageV = 68.0 + 3.0 * sin(t * 0.08),
        currentA = 25.0 + 8.0 * sin(t * 0.15),
        tempC = 52 + (5 * sin(t * 0.03)).toInt(),
        mode = 3, // SPORT
    )

    private fun lowBattery(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = 20.0 + 5.0 * sin(t * 0.3),
        battery = (12 - tick * 0.001).toInt().coerceIn(3, 15),
        voltageV = 58.0 + sin(t * 0.2),
        currentA = 3.0 + Random.nextDouble() * 2.0,
        tempC = 35,
        mode = 1, // ECO
    )

    private fun overheating(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = 50.0 + 10.0 * sin(t * 0.15),
        battery = 45,
        voltageV = 70.0,
        currentA = 20.0 + 5.0 * sin(t * 0.2),
        tempC = (68 + (5 * sin(t * 0.1)).toInt()).coerceAtMost(85),
        mode = 2, // NORMAL
    )

    private fun regenBraking(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = (60.0 - t * 2.0).coerceIn(0.0, 60.0),
        battery = (50 + tick * 0.002).toInt().coerceIn(50, 55),
        voltageV = 74.0 + 2.0 * sin(t * 0.3),
        currentA = -15.0 + 5.0 * sin(t * 0.5), // Negative = regen
        tempC = 40,
        mode = 4, // REGEN — wire value the mapper decodes to VehicleMode.REGEN
    )

    private fun parked(tick: Long) = ScenarioFrame(
        speedKmh = 0.0,
        battery = 95,
        voltageV = 76.8,
        currentA = 0.2 + Random.nextDouble() * 0.1,
        tempC = 25,
        mode = 0, // PARK
    )

    /**
     * Linear speed sweep for exercising the Drive gauge over its full 0..180 km/h
     * scale: a triangle wave that ramps 0→180 then 180→0 (24 s per full cycle, ~15
     * km/h·s). Current tracks speed so the Current/Power tiles move with it.
     */
    private fun gaugeSweep(t: Double): ScenarioFrame {
        val period = 24.0
        val phase = (t % period) / period                                  // 0..1
        val speed = (if (phase < 0.5) 360.0 * phase else 360.0 * (1.0 - phase))
            .coerceIn(0.0, 180.0)                                          // 0→180→0
        return ScenarioFrame(
            speedKmh = speed,
            battery = 80,
            voltageV = 72.0,
            currentA = speed * 0.35,
            tempC = 40,
            mode = 3, // SPORT
        )
    }

    private fun sportMode(t: Double, tick: Long) = ScenarioFrame(
        speedKmh = (20.0 + t * 5.0).coerceAtMost(110.0) * (0.8 + 0.2 * sin(t * 0.5)),
        battery = (80 - tick * 0.015).toInt().coerceIn(20, 100),
        voltageV = 66.0 + 6.0 * sin(t * 0.2),
        currentA = 35.0 + 15.0 * sin(t * 0.4),
        tempC = (45 + (tick * 0.02).toInt()).coerceAtMost(72),
        mode = 3, // SPORT
    )
}

/**
 * Wire-level scenario sample. `tempC` is the motor temperature; the battery
 * and controller readings are independent channels (telemetry.capnp v2+).
 * Heuristic offsets here are local to the simulator only — the live decode
 * path reads them straight off the wire without any synthesis.
 */
data class ScenarioFrame(
    val speedKmh: Double,
    val battery: Int,
    val voltageV: Double,
    val currentA: Double,
    val tempC: Int,
    val mode: Int,
    val batteryTempC: Int = (tempC - 12).coerceAtLeast(-40),
    val controllerTempC: Int = (tempC - 7).coerceAtLeast(-40)
)
