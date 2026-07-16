package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema.PhoneNotification

/**
 * Builds the board's trip-odometer reset commands (docs/capnpble_new.md §5b). Each is a
 * `PhoneNotification` on the `0xAF07` control channel — same frozen contract as
 * `WIFI_*` / `TIME_SYNC` (`category = 32`, `appName = "EVD"`, command in `title`).
 *
 * `ODO_RESET_TRIP_A` / `ODO_RESET_TRIP_B` zero the respective resettable trip meter on
 * the board (lifetime odometer untouched); the wire `tripAMeters`/`tripBMeters` return
 * to 0 on the next uplink. The legacy `ODO_RESET_TRIP` title (= trip A) is deprecated
 * for new apps — we send the explicit per-trip titles.
 */
object TripOdometerCommands {

    const val CMD_RESET_TRIP_A = "ODO_RESET_TRIP_A"
    const val CMD_RESET_TRIP_B = "ODO_RESET_TRIP_B"

    private const val APP = "EVD"

    fun resetTripA(): PhoneNotification = command(CMD_RESET_TRIP_A)

    fun resetTripB(): PhoneNotification = command(CMD_RESET_TRIP_B)

    private fun command(name: String) = PhoneNotification(
        id = 0,
        category = PhoneNotificationSchema.CATEGORY_CONTROL,
        appName = APP,
        title = name,
    )
}
