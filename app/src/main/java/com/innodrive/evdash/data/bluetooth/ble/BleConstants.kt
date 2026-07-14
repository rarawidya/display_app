package com.innodrive.evdash.data.bluetooth.ble

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
 *     • 0xAF05  NOTIFY  + CCCD   → control events (board → phone)    [CONTROL]
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

    /**
     * Control-event notify characteristic (board → phone): call answer/end button
     * taps from the cluster (docs/CALL-CONTROL-INTEGRATION.md). Optional — absent
     * on firmware without call control; the subscribe is skipped and the feature
     * stays off. The phone never writes this characteristic.
     */
    val CONTROL_CHAR_UUID: UUID = uuid16("AF05")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /* --- Standard Device Information Service (read once on connect for metadata) --- */
    /** Device Information Service. Absent on firmware that doesn't implement it. */
    val DIS_SERVICE_UUID: UUID = uuid16("180A")
    /** Model Number String characteristic (`0x2A24`). */
    val DIS_MODEL_UUID: UUID = uuid16("2A24")
    /** Firmware Revision String characteristic (`0x2A26`). */
    val DIS_FIRMWARE_UUID: UUID = uuid16("2A26")

    /** MTU we request on connect — 247 → 244 B payload, a whole frame per notification. */
    const val PREFERRED_MTU = 247

    private fun uuid16(hex4: String): UUID =
        UUID.fromString("0000${hex4.lowercase()}-0000-1000-8000-00805f9b34fb")
}
