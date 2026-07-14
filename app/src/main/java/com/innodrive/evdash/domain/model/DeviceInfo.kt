package com.innodrive.evdash.domain.model

/**
 * Vehicle metadata read once per connection from the BLE **Device Information
 * Service** (`0x180A`) — model number (`0x2A24`) and firmware revision
 * (`0x2A26`). Fields are null until the read completes, or when the board
 * doesn't expose that characteristic. Surfaced on the Home "Vehicle
 * Information" card so Firmware / Model reflect the real device instead of a
 * hardcoded constant.
 */
data class DeviceInfo(
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
)
