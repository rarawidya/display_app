package com.example.displayapp.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedDeque

/** Severity for a diagnostics log entry. */
enum class DiagSeverity { INFO, WARN, ERROR }

/** A single line in the diagnostics log ring buffer. */
data class DiagnosticsLogEntry(
    val timestampMs: Long,
    val severity: DiagSeverity,
    val tag: String,
    val message: String
)

/** Live aggregate diagnostics counters. */
data class DiagnosticsSnapshot(
    val framesPerSecond: Int = 0,
    val framesDecoded: Long = 0,
    val crcErrors: Long = 0,
    val syncLosses: Long = 0,
    val reconnects: Long = 0,
    /** Phone notifications successfully written to the board (0xAF07). */
    val notificationsPushed: Long = 0,
    /** Phone notifications that couldn't be delivered (no link / write failed). */
    val notificationsDropped: Long = 0,
    val lastUpdateMs: Long = 0
)

/**
 * Process-wide diagnostics hub.
 *
 * Owns a [StateFlow] of [DiagnosticsSnapshot] for live counters and a fixed-size
 * ring buffer of log entries. Both the Drive cockpit (overlay) and the Settings
 * screen subscribe; the BT data source / repository / reconnect policy write.
 *
 * Implementation notes:
 * - The log buffer is a `ConcurrentLinkedDeque` so callers from any thread can
 *   push without lock contention. Buffer size is capped — older entries are
 *   evicted when full. Reads snapshot to a list before exposing via the Flow.
 * - Snapshot updates are coalesced through `MutableStateFlow.update` so
 *   concurrent producers don't lose increments.
 */
class DiagnosticsRepository(
    private val maxLogEntries: Int = 200
) {
    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    private val logBuffer = ConcurrentLinkedDeque<DiagnosticsLogEntry>()

    private val _logs = MutableStateFlow<List<DiagnosticsLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticsLogEntry>> = _logs.asStateFlow()

    /* ---------------------- snapshot writers ---------------------- */

    fun reportFrame(decoded: Boolean = true) {
        _snapshot.update {
            if (decoded) it.copy(framesDecoded = it.framesDecoded + 1, lastUpdateMs = System.currentTimeMillis())
            else it
        }
    }

    fun reportCrcError() {
        _snapshot.update { it.copy(crcErrors = it.crcErrors + 1) }
        log(DiagSeverity.WARN, "FrameDecoder", "CRC mismatch")
    }

    fun reportSyncLoss() {
        _snapshot.update { it.copy(syncLosses = it.syncLosses + 1) }
        log(DiagSeverity.WARN, "FrameDecoder", "Sync lost")
    }

    fun reportReconnect() {
        _snapshot.update { it.copy(reconnects = it.reconnects + 1) }
        log(DiagSeverity.INFO, "ReconnectPolicy", "Bluetooth reconnect attempt")
    }

    fun reportFps(fps: Int) {
        _snapshot.update { it.copy(framesPerSecond = fps) }
    }

    /** Record a phone→board notification push outcome (see [PhoneNotificationSender]). */
    fun reportNotificationPush(delivered: Boolean) {
        _snapshot.update {
            if (delivered) it.copy(notificationsPushed = it.notificationsPushed + 1)
            else it.copy(notificationsDropped = it.notificationsDropped + 1)
        }
    }

    fun reset() {
        _snapshot.value = DiagnosticsSnapshot()
        logBuffer.clear()
        _logs.value = emptyList()
    }

    /* ---------------------- log buffer ---------------------- */

    fun log(severity: DiagSeverity, tag: String, message: String) {
        val entry = DiagnosticsLogEntry(System.currentTimeMillis(), severity, tag, message)
        logBuffer.addLast(entry)
        while (logBuffer.size > maxLogEntries) logBuffer.pollFirst()
        // Newest-first for the UI
        _logs.value = logBuffer.toList().asReversed()
    }
}
