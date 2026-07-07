package com.example.displayapp.data.bluetooth.ble

import java.util.UUID

/**
 * BLE GATT contract for the controller — **confirmed by firmware**
 * (`docs/EVDISPLAY_BLE_COMMUNICATION.md`, v1.0, SG2002 + AIC8800D80).
 *
 * Device: name `EVdisplay` (scan response), **public/stable address**, advertises
 * service `0xAF00`, open GATT (no bonding). MTU 247 required for one-notification-
 * per-frame (44-byte frames). Centralised here so any firmware change is a one-line edit.
 *
 *   SERVICE 0xAF00
 *     • 0xAF08  NOTIFY  + CCCD   → telemetry stream (board → phone)   [TX]
 *     • 0xAF07  WRITE / WRITE_NR → notification channel (phone → board) [RX]
 *     • 0xAF06  WRITE / WRITE_NR → navigation channel (phone → board)   [NAV]
 */
object BleConstants {

    /** Vendor serial service that carries telemetry. Also the scan filter target. */
    val SERVICE_UUID: UUID = uuid16("AF00")

    /** Telemetry notify characteristic (board → phone). */
    val TX_CHAR_UUID: UUID = uuid16("AF08")

    /** Notification write characteristic (phone → board), PhoneNotification. */
    val RX_CHAR_UUID: UUID = uuid16("AF07")

    /**
     * Navigation write characteristic (phone → board), NavInstruction / RouteSummary
     * (docs/NAVIGATION-INTEGRATION.md). Separate from the notification channel so the
     * verified 0xAF07 path is untouched. Absent on pre-nav firmware → nav disabled.
     */
    val NAV_CHAR_UUID: UUID = uuid16("AF06")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU we request on connect — 247 → 244 B payload, a whole frame per notification. */
    const val PREFERRED_MTU = 247

    private fun uuid16(hex4: String): UUID =
        UUID.fromString("0000${hex4.lowercase()}-0000-1000-8000-00805f9b34fb")
}
