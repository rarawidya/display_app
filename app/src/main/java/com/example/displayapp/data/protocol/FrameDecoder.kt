package com.example.displayapp.data.protocol

import timber.log.Timber

/**
 * State-machine frame decoder for the binary telemetry protocol.
 *
 * Frame format:
 *   [SYNC_HI: 0xCA] [SYNC_LO: 0xFE] [LEN_LO] [LEN_HI] [PAYLOAD...] [CRC_LO] [CRC_HI]
 *
 * The decoder processes bytes one-at-a-time (or in chunks), accumulates a complete frame,
 * validates CRC, and emits the payload via the callback. On corruption, it resyncs by
 * searching for the next SYNC pair.
 *
 * Thread-safety: NOT thread-safe. Call [feed] from a single coroutine.
 */
class FrameDecoder(
    private val onFrame: (ByteArray) -> Unit,
    private val onCrcError: () -> Unit = {},
    private val onSyncLoss: () -> Unit = {}
) {
    private enum class State {
        SYNC_HI,
        SYNC_LO,
        LENGTH_LO,
        LENGTH_HI,
        PAYLOAD,
        CRC_LO,
        CRC_HI
    }

    private var state = State.SYNC_HI
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
        state = State.SYNC_HI
        payloadIndex = 0
    }

    private fun processByte(b: Int) {
        when (state) {
            State.SYNC_HI -> {
                if (b == SYNC_BYTE_HI) {
                    state = State.SYNC_LO
                }
            }

            State.SYNC_LO -> {
                if (b == SYNC_BYTE_LO) {
                    state = State.LENGTH_LO
                } else if (b == SYNC_BYTE_HI) {
                    // Stay in SYNC_LO — could be 0xCA 0xCA 0xFE sequence
                    state = State.SYNC_LO
                } else {
                    syncLosses++
                    onSyncLoss()
                    state = State.SYNC_HI
                }
            }

            State.LENGTH_LO -> {
                payloadLength = b
                state = State.LENGTH_HI
            }

            State.LENGTH_HI -> {
                payloadLength = payloadLength or (b shl 8)
                if (payloadLength == 0 || payloadLength > MAX_PAYLOAD) {
                    Timber.w("Invalid payload length: $payloadLength, resyncing")
                    syncLosses++
                    onSyncLoss()
                    state = State.SYNC_HI
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

                state = State.SYNC_HI
            }
        }
    }

    private fun computeFrameCrc(): Int {
        // CRC covers the 2-byte length field + payload
        val crcBuffer = ByteArray(2 + payloadLength)
        crcBuffer[0] = (payloadLength and 0xFF).toByte()
        crcBuffer[1] = ((payloadLength shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, crcBuffer, 2, payloadLength)
        return Crc16.compute(crcBuffer)
    }

    companion object {
        const val SYNC_BYTE_HI = 0xCA
        const val SYNC_BYTE_LO = 0xFE
        const val MAX_PAYLOAD = 512
    }
}

data class FrameStats(
    val framesDecoded: Long,
    val crcErrors: Long,
    val syncLosses: Long
)
