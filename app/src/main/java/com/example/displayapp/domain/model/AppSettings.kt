package com.example.displayapp.domain.model

import com.example.displayapp.data.simulator.TelemetryScenario

/* -------------------------------------------------------------------------- */
/*  Unit preferences                                                           */
/* -------------------------------------------------------------------------- */

enum class SpeedUnit(val label: String, val suffix: String) {
    KMH("Kilometres / hour", "km/h"),
    MPH("Miles / hour", "mph");

    /** Convert a value already in km/h to this unit. */
    fun convertFromKmh(kmh: Float): Float = if (this == MPH) kmh * 0.621371f else kmh

    /** Display string for a speed coming from telemetry (km/h). */
    fun formatSpeed(kmh: Float, decimals: Int = 0): String =
        "%.${decimals}f %s".format(convertFromKmh(kmh), suffix)

    /** Display string for a distance (metres in → km/mi out). */
    fun formatDistance(meters: Long, decimals: Int = 1): String {
        val km = meters / 1000f
        return if (this == MPH) "%.${decimals}f mi".format(km * 0.621371f)
        else "%.${decimals}f km".format(km)
    }

    val distanceSuffix: String get() = if (this == MPH) "mi" else "km"
}

enum class TemperatureUnit(val label: String, val suffix: String) {
    CELSIUS("Celsius", "°C"),
    FAHRENHEIT("Fahrenheit", "°F");

    fun convertFromCelsius(c: Float): Float =
        if (this == FAHRENHEIT) c * 9f / 5f + 32f else c

    fun formatTemp(celsius: Float, decimals: Int = 0): String =
        "%.${decimals}f %s".format(convertFromCelsius(celsius), suffix)
}

enum class TimeFormat(val label: String) {
    H12("12-hour"),
    H24("24-hour");
}

/* -------------------------------------------------------------------------- */
/*  Recording preferences                                                      */
/* -------------------------------------------------------------------------- */

enum class RetentionPeriod(val days: Int, val label: String) {
    DAYS_30(30,   "30 days"),
    DAYS_90(90,   "90 days"),
    DAYS_365(365, "1 year"),
    FOREVER(-1,   "Forever");
}

/* -------------------------------------------------------------------------- */
/*  Snapshot                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * User-controlled app preferences.
 *
 * Sample-rate and auto-start were removed in favor of always-on recording at
 * the protocol's native rate — they were placebo toggles before, and the
 * Settings screen no longer pretends to expose them.
 *
 * Dynamic color + diagnostics-overlay + simulator-scenario remain in the model
 * but are now reached through Developer Mode (gated by [devModeUnlocked]).
 */
data class AppSettings(
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val timeFormat: TimeFormat = TimeFormat.H24,

    val retention: RetentionPeriod = RetentionPeriod.DAYS_90,

    val simulatorMode: Boolean = true,
    val simulatorScenario: TelemetryScenario = TelemetryScenario.CITY_CRUISE,
    val autoConnect: Boolean = true,

    val showDiagnosticsOverlay: Boolean = false,

    /**
     * Mirror the phone's status-bar notifications to the board's on-screen banner
     * (docs/APP-NOTIFICATION-INTEGRATION.md). Off by default — also requires the
     * system "Notification access" grant. Gates delivery in NotificationRelayService.
     */
    val notificationRelayEnabled: Boolean = false,

    /** Set true once the user taps version row 7 times. Survives app restart. */
    val devModeUnlocked: Boolean = false
)
