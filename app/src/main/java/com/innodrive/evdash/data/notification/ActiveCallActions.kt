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

    /**
     * Immutable snapshot of the current call. `set()` runs on the binder thread
     * (notification posted/removed) while `current()` runs on the `0xAF05`
     * control-frame collector coroutine — reading `actions`, `sourceKey`, and
     * `stampMs` as three separate `@Volatile` fields let a reader pair a fresh
     * `actions` with a stale `stampMs` and false-expire a live call action (board
     * Answer/End then no-ops right when it matters). Keeping all three in one
     * `@Volatile` reference publishes them atomically, so a reader always sees a
     * consistent triple.
     */
    private data class Snapshot(val actions: CallActions, val sourceKey: String?, val stampMs: Long)

    @Volatile private var snapshot: Snapshot? = null

    fun set(a: CallActions, key: String?) {
        snapshot = Snapshot(a, key, SystemClock.elapsedRealtime())
    }

    fun clear() {
        snapshot = null
    }

    /**
     * Clear only when [key] is the notification that populated the current actions.
     * A call goes ring → answered as separate notifications; removing the
     * superseded ring banner must not wipe the active call's captured hang-up.
     */
    fun clearIfSource(key: String?) {
        val s = snapshot ?: return
        if (key != null && key == s.sourceKey) clear()
    }

    /** The current call actions if still fresh, else null (and cleared). */
    fun current(): CallActions? {
        val s = snapshot ?: return null
        if (SystemClock.elapsedRealtime() - s.stampMs > TTL_MS) {
            clear()
            return null
        }
        return s.actions
    }
}
