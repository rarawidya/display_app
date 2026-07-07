package com.example.displayapp.data.notification

import com.example.displayapp.data.bluetooth.BluetoothDataSource
import com.example.displayapp.data.diagnostics.DiagnosticsRepository
import com.example.displayapp.data.protocol.FrameEncoder
import com.example.displayapp.data.protocol.PhoneNotificationSchema
import com.example.displayapp.data.protocol.PhoneNotificationSchema.PhoneNotification
import timber.log.Timber

/**
 * Encodes a [PhoneNotification] and pushes it to the board in **one** BLE write
 * (docs/APP-NOTIFICATION-INTEGRATION.md). Stateless beyond the data-source handle —
 * gating (enabled toggle) and classification live in the caller
 * ([com.example.displayapp.service.NotificationRelayService]).
 *
 * Reuses the existing, unit-tested [PhoneNotificationSchema] + [FrameEncoder]
 * pipeline, then hands the framed bytes to whichever transport is active via
 * [BluetoothDataSource.writeCommand] (a no-op that returns false on the simulator
 * or while disconnected). Each outcome is recorded in [DiagnosticsRepository] so
 * the pushed/dropped counters surface on the Drive overlay and Developer screen.
 */
class PhoneNotificationSender(
    private val dataSource: BluetoothDataSource,
    private val diagnostics: DiagnosticsRepository
) {

    suspend fun send(notification: PhoneNotification): Boolean {
        val frame = FrameEncoder.encode(PhoneNotificationSchema.encode(notification))
        val ok = dataSource.writeCommand(frame)
        diagnostics.reportNotificationPush(delivered = ok)
        Timber.tag(TAG).d(
            "push id=%d cat=%d flags=0x%02X → %s",
            notification.id, notification.category, notification.flags,
            if (ok) "delivered" else "dropped"
        )
        return ok
    }

    private companion object {
        const val TAG = "NotifRelay"
    }
}
