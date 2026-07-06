package com.example.displayapp.data.protocol

import timber.log.Timber

/**
 * State-machine frame decoder for the EVdashboard (Votol) telemetry protocol.
 *
 * Frame format (capnp.md §2):
 *   [SYNC: 0xAA] [LEN: uint8] [PAYLOAD...] [CRC_LO] [CRC_HI]
 *
 * - SYNC is a single `0xAA` byte; scan for it to (re)synchronise.
 * - LEN is a single unsigned byte (payload length). Always trust it — never
 *   hardcode 40; the frame grows if the schema gains fields.
 * - CRC16-CCITT is computed over `LEN || PAYLOAD` (the length byte followed by
 *   the payload) and transmitted little-endian.
 *
 * The decoder processes bytes one-at-a-time (or in chunks), accumulates a
 * complete frame, validates CRC, and emits the payload via the callback. On
 * corruption it resyncs by searching for the next SYNC byte — a `0xAA` can
 * legitimately occur inside the payload, so a failed CRC just keeps scanning
 * and the next real frame resyncs.
 *
 * Thread-safety: NOT thread-safe. Call [feed] from a single coroutine.
 */
class FrameDecoder(
    private val onFrame: (ByteArray) -> Unit,
    private val onCrcError: () -> Unit = {},
    private val onSyncLoss: () -> Unit = {}
) {
    private enum class State {
        SYNC,
        LENGTH,
        PAYLOAD,
        CRC_LO,
        CRC_HI
    }

    private var state = State.SYNC
    private var payloadLength = 0
    private var payloadIndex = 0
    private var payload = ByteArray(MAX_PAYLOAD)
    private var crcLo = 0

    private var framesDecoded = 0L
    private var crcErrors = 0L
    private var syncLosses = 0L

    val stats: FrameStats get() = FrameStats(framesDecoded, crcErrors, syncLosses)

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (i in offset until offset + length) {
            processByte(data[i].toInt() and 0xFF)
        }
    }

    fun reset() {
        state = State.SYNC
        payloadIndex = 0
    }

    private fun processByte(b: Int) {
        when (state) {
            State.SYNC -> {
                if (b == SYNC_BYTE) {
                    state = State.LENGTH
                }
            }

            State.LENGTH -> {
                payloadLength = b
                if (payloadLength == 0 || payloadLength > MAX_PAYLOAD) {
                    Timber.w("Invalid payload length: $payloadLength, resyncing")
                    syncLosses++
                    onSyncLoss()
                    state = State.SYNC
                } else {
                    if (payload.size < payloadLength) {
                        payload = ByteArray(payloadLength)
                    }
                    payloadIndex = 0
                    state = State.PAYLOAD
                }
            }

            State.PAYLOAD -> {
                payload[payloadIndex++] = b.toByte()
                if (payloadIndex >= payloadLength) {
                    state = State.CRC_LO
                }
            }

            State.CRC_LO -> {
                crcLo = b
                state = State.CRC_HI
            }

            State.CRC_HI -> {
                val receivedCrc = crcLo or (b shl 8)
                val computedCrc = computeFrameCrc()

                if (receivedCrc == computedCrc) {
                    framesDecoded++
                    val frame = payload.copyOf(payloadLength)
                    onFrame(frame)
                } else {
                    crcErrors++
                    Timber.w("CRC mismatch: received=0x${receivedCrc.toString(16)}, computed=0x${computedCrc.toString(16)}")
                    onCrcError()
                }

                state = State.SYNC
            }
        }
    }

    private fun computeFrameCrc(): Int {
        // CRC covers the 1-byte length field + payload (capnp.md §2).
        val crcBuffer = ByteArray(1 + payloadLength)
        crcBuffer[0] = (payloadLength and 0xFF).toByte()
        System.arraycopy(payload, 0, crcBuffer, 1, payloadLength)
        return Crc16.compute(crcBuffer)
    }

    companion object {
        const val SYNC_BYTE = 0xAA

        // LEN is a uint8, so the payload can never exceed 255 bytes on the wire.
        const val MAX_PAYLOAD = 255
    }
}

data class FrameStats(
    val framesDecoded: Long,
    val crcErrors: Long,
    val syncLosses: Long
)
