package com.example.displayapp.data.notification

import com.example.displayapp.data.protocol.PhoneNotificationSchema
import com.example.displayapp.data.protocol.PhoneNotificationSchema.PhoneNotification

/**
 * Builds the board's Wi-Fi STA control commands (docs/BOARD-WIFI-STA-INTEGRATION.md §2):
 * the phone hands its hotspot credentials to the board over the `0xAF07` control
 * channel so the board can join as a Wi-Fi client and download map packs.
 *
 * Same frozen `PhoneNotification` control contract as `ODO_RESET_TRIP`
 * (capnpble.md §5b): `category = 32`, `appName = "EVD"`, command in `title`,
 * argument in `body`. SSID/PSK only STAGE values on the board; `WIFI_JOIN` commits.
 */
object BoardWifiCommands {

    const val CMD_SSID = "WIFI_SSID"
    const val CMD_PSK = "WIFI_PSK"
    const val CMD_JOIN = "WIFI_JOIN"
    const val CMD_FORGET = "WIFI_FORGET"

    private const val APP = "EVD"

    /** The join sequence — send in order, each as a reliable write. */
    fun joinSequence(ssid: String, passphrase: String): List<PhoneNotification> = listOf(
        command(CMD_SSID, ssid),
        command(CMD_PSK, passphrase),
        command(CMD_JOIN),
    )

    /** Wipe the board's stored credentials and return it to AP mode. */
    fun forget(): PhoneNotification = command(CMD_FORGET)

    private fun command(name: String, body: String = "") = PhoneNotification(
        id = 0,
        category = PhoneNotificationSchema.CATEGORY_CONTROL,
        appName = APP,
        title = name,
        body = body,
    )
}
