package com.example.displayapp.data.persistence

import com.example.displayapp.data.persistence.dao.TelemetryDao
import com.example.displayapp.data.persistence.entity.TelemetryEntity
import com.example.displayapp.domain.model.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * High-frequency telemetry logger with ring buffer and batch inserts.
 *
 * Architecture:
 * - Incoming telemetry at 20Hz is written to an in-memory ring buffer (lock-free single-producer)
 * - A flush coroutine drains the buffer every [FLUSH_INTERVAL_MS] and batch-inserts to Room
 * - This avoids 20 individual INSERT/s (which would trash SQLite WAL performance)
 * - If the buffer overflows (flush too slow), oldest samples are silently dropped
 *
 * Performance characteristics:
 * - 20Hz input → ~20 rows accumulated per second
 * - Flush every 1s → 1 batch INSERT of ~20 rows
 * - SQLite WAL mode handles this efficiently (~1ms per batch)
 * - Total I/O: ~1 write transaction per second
 *
 * Why a ring buffer instead of a Channel:
 * - Zero allocation on the hot path (no suspend, no object wrapping)
 * - Bounded memory regardless of consumer speed
 * - Overflow policy is "drop oldest" which is correct for telemetry
 *   (we always want the latest data, stale data is worthless)
 */
class TelemetryLogger(
    private val telemetryDao: TelemetryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var activeTripId: Long? = null

    // Ring buffer: fixed-size array, head advances on write, tail on read
    private val buffer = arrayOfNulls<TelemetryEntity>(BUFFER_CAPACITY)
    private var writePos = 0
    private var readPos = 0
    private var count = 0

    val isRecording: Boolean get() = activeTripId != null

    fun startRecording(tripId: Long) {
        activeTripId = tripId
        writePos = 0
        readPos = 0
        count = 0
        startFlushLoop()
        Timber.i("Telemetry recording started for trip $tripId")
    }

    fun stopRecording() {
        flushJob?.cancel()
        flushJob = null
        // Final flush
        scope.launch { flush() }
        Timber.i("Telemetry recording stopped for trip $activeTripId")
        activeTripId = null
    }

    /**
     * Called from the telemetry pipeline at 20Hz.
     * Must be fast — no suspension, no allocation beyond the entity.
     */
    fun log(data: VehicleData) {
        val tripId = activeTripId ?: return

        val entity = TelemetryEntity(
            tripId = tripId,
            timestamp = data.timestamp,
            speed = data.speed * 10,
            battery = data.batteryPercent,
            voltage = (data.voltage * 100).toInt(),
            current = (data.current * 100).toInt(),
            temperature = data.temperature,
            mode = data.vehicleMode.ordinal,
            batteryTemperature = data.batteryTemperature,
            controllerTemperature = data.controllerTemperature
        )

        // Write to ring buffer
        synchronized(buffer) {
            buffer[writePos] = entity
            writePos = (writePos + 1) % BUFFER_CAPACITY
            if (count < BUFFER_CAPACITY) {
                count++
            } else {
                // Overflow: advance read position (drop oldest)
                readPos = (readPos + 1) % BUFFER_CAPACITY
            }
        }
    }

    fun release() {
        stopRecording()
        scope.cancel()
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    private suspend fun flush() {
        val batch = drain()
        if (batch.isEmpty()) return

        try {
            telemetryDao.insertBatch(batch)
        } catch (e: Exception) {
            Timber.e(e, "Failed to flush ${batch.size} telemetry samples")
        }
    }

    private fun drain(): List<TelemetryEntity> {
        synchronized(buffer) {
            if (count == 0) return emptyList()
            val result = ArrayList<TelemetryEntity>(count)
            repeat(count) {
                buffer[readPos]?.let { result.add(it) }
                buffer[readPos] = null
                readPos = (readPos + 1) % BUFFER_CAPACITY
            }
            count = 0
            return result
        }
    }

    companion object {
        private const val BUFFER_CAPACITY = 128 // ~6 seconds at 20Hz
        private const val FLUSH_INTERVAL_MS = 1000L
    }
}
