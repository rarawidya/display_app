package com.innodrive.evdash.data.notification

import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema.PhoneNotification

/**
 * Builds the board's `TIME_SYNC` control command (docs/TIME-SYNC-INTEGRATION.md).
 *
 * The SG2002 has no battery-backed RTC and no NTP — every boot it starts at ~2018,
 * so the phone is the time source. Same frozen `PhoneNotification` control contract
 * as `ODO_RESET_TRIP` / `WIFI_*` (capnpble.md §5b): `category = 32`, `appName = "EVD"`,
 * command in `title`, newline `key=value` lines in `body`. No new schema/characteristic.
 *
 * Body (`tz_offset_min` optional but always sent so the board renders local time):
 * ```
 * epoch=1784219400
 * tz_offset_min=420
 * ```
 * `epoch` = UTC seconds since 1970; `tz_offset_min` = phone's signed UTC offset in
 * minutes (e.g. 420 = UTC+7 WIB). The board validates `epoch` to a sane range and
 * ignores garbage, so a stale/zero value is safely a no-op board-side.
 */
object TimeSyncCommand {

    const val CMD_TIME_SYNC = "TIME_SYNC"

    private const val APP = "EVD"

    fun build(epochSeconds: Long, tzOffsetMinutes: Int): PhoneNotification = PhoneNotification(
        id = 0,
        category = PhoneNotificationSchema.CATEGORY_CONTROL,
        appName = APP,
        title = CMD_TIME_SYNC,
        body = "epoch=$epochSeconds\ntz_offset_min=$tzOffsetMinutes",
    )
}
