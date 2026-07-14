package com.innodrive.evdash.data.notification

import android.os.SystemClock

/**
 * App-scoped holder for the current call's fireable [CallActions] — populated by
 * [com.innodrive.evdash.service.NotificationRelayService] when a call notification
 * is posted, consumed by [CallControlHandler] when a board Answer/End button event
 * arrives over `0xAF05`.
 *
 * One live call at a time (latest-wins) and self-expiring, so a stale action left
 * over from a call that already ended is never fired. Holds only PendingIntents
 * created by the calling app; nothing here is sent over BLE or off the device.
 */
object ActiveCallActions {

    // A ringing/active call rarely outlives this; past it we assume the holder is
    // stale (removal callbacks are best-effort) and refuse to fire.
    private const val TTL_MS = 60_000L

    @Volatile private var actions: CallActions? = null
    @Volatile private var sourceKey: String? = null
    @Volatile private var stampMs: Long = 0L

    fun set(a: CallActions, key: String?) {
        actions = a
        sourceKey = key
        stampMs = SystemClock.elapsedRealtime()
    }

    fun clear() {
        actions = null
        sourceKey = null
        stampMs = 0L
    }

    /**
     * Clear only when [key] is the notification that populated the current actions.
     * A call goes ring → answered as separate notifications; removing the
     * superseded ring banner must not wipe the active call's captured hang-up.
     */
    fun clearIfSource(key: String?) {
        if (key != null && key == sourceKey) clear()
    }

    /** The current call actions if still fresh, else null (and cleared). */
    fun current(): CallActions? {
        val a = actions ?: return null
        if (SystemClock.elapsedRealtime() - stampMs > TTL_MS) {
            clear()
            return null
        }
        return a
    }
}
