package com.innodrive.evdash.data.notification

import android.os.SystemClock
import com.innodrive.evdash.data.bluetooth.BluetoothDataSource
import com.innodrive.evdash.data.diagnostics.DiagnosticsRepository
import com.innodrive.evdash.data.protocol.FrameEncoder
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema
import com.innodrive.evdash.data.protocol.PhoneNotificationSchema.PhoneNotification
import com.innodrive.evdash.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Encodes a [PhoneNotification] and pushes it to the board in **one** BLE write
 * (docs/APP-NOTIFICATION-INTEGRATION.md). Gating (enabled toggle) and classification
 * live in the caller ([com.innodrive.evdash.service.NotificationRelayService]).
 *
 * Reuses the existing, unit-tested [PhoneNotificationSchema] + [FrameEncoder]
 * pipeline, then hands the framed bytes to whichever transport is active via
 * [BluetoothDataSource.writeCommand] (a no-op that returns false on the simulator
 * or while disconnected). Each outcome is recorded in [DiagnosticsRepository] so
 * the pushed/dropped counters surface on the Drive overlay and Developer screen.
 *
 * **Reconnect buffer.** A notification posted while the link isn't `CONNECTED` (the
 * frame-watchdog force-close + rescan reconnect can take seconds) would otherwise be
 * dropped with no retry. Such sends are stashed in a small, freshness-limited buffer
 * and replayed once the link returns, so a message that arrives during a brief
 * reconnect gap still reaches the display instead of vanishing. Replay is bounded by
 * [REPLAY_TTL_MS] (a much older banner is stale, not worth flashing), capped at
 * [MAX_BUFFERED] entries, and deduped by id (the board is latest-wins by id, so only
 * the newest frame per alert is worth replaying).
 */
class PhoneNotificationSender(
    private val dataSource: BluetoothDataSource,
    private val diagnostics: DiagnosticsRepository,
    scope: CoroutineScope
) {

    private data class Pending(val notification: PhoneNotification, val stampMs: Long)

    private val bufferMutex = Mutex()
    private val buffer = ArrayDeque<Pending>()

    init {
        // Replay buffered notifications when the link (re)connects.
        scope.launch {
            dataSource.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) flushBuffer()
            }
        }
    }

    suspend fun send(notification: PhoneNotification): Boolean {
        val ok = write(notification)
        if (!ok && dataSource.connectionState.value != ConnectionState.CONNECTED) {
            // Link isn't ready (disconnected/reconnecting) — hold it for replay rather
            // than dropping it. A write that fails while CONNECTED is a genuine failure
            // (e.g. frame > MTU), not a reconnect gap, so it is not buffered.
            enqueue(Pending(notification, SystemClock.elapsedRealtime()))
        }
        return ok
    }

    /** Encode + push one frame, recording the outcome. */
    private suspend fun write(notification: PhoneNotification): Boolean {
        val frame = FrameEncoder.encode(PhoneNotificationSchema.encode(notification))
        // Pre-write log (NOTIFICATION-APP-FIXME.md §2): pairs with the board's
        // `DOWNLINK raw` line to localize a loss between encoder and radio.
        Timber.tag(TAG).i(
            "writing 0xAF07 frame len=%d id=%d cat=%d", frame.size, notification.id, notification.category
        )
        val ok = dataSource.writeCommand(frame)
        diagnostics.reportNotificationPush(delivered = ok)
        Timber.tag(TAG).d(
            "push id=%d cat=%d flags=0x%02X → %s",
            notification.id, notification.category, notification.flags,
            if (ok) "delivered" else "dropped"
        )
        return ok
    }

    private suspend fun enqueue(pending: Pending) = bufferMutex.withLock {
        // Latest-wins by id: keep only the newest frame per alert.
        buffer.removeAll { it.notification.id == pending.notification.id }
        buffer.addLast(pending)
        while (buffer.size > MAX_BUFFERED) buffer.removeFirst()
        Timber.tag(TAG).d("buffered id=%d for replay (%d held)", pending.notification.id, buffer.size)
    }

    /** Drain fresh buffered notifications and replay them, oldest first. */
    private suspend fun flushBuffer() {
        val now = SystemClock.elapsedRealtime()
        val toReplay = bufferMutex.withLock {
            if (buffer.isEmpty()) return
            val fresh = buffer.filter { now - it.stampMs <= REPLAY_TTL_MS }
            buffer.clear()
            fresh
        }
        if (toReplay.isEmpty()) return
        // connectionState flips to CONNECTED at the end of the GATT handshake, but give
        // the link a brief beat before writing so the first replay doesn't race a
        // still-settling stack.
        delay(REPLAY_SETTLE_MS)
        Timber.tag(TAG).i("replaying %d buffered notification(s) after reconnect", toReplay.size)
        for (p in toReplay) write(p.notification)
    }

    private companion object {
        const val TAG = "NotifRelay"

        /** Don't replay a banner older than this — it's stale, not worth flashing. */
        const val REPLAY_TTL_MS = 15_000L

        /** Let the freshly-connected link settle before the first replay write. */
        const val REPLAY_SETTLE_MS = 400L

        /** Cap held frames so a long outage can't grow the buffer unbounded. */
        const val MAX_BUFFERED = 10
    }
}
