package com.example.displayapp.data.protocol

/**
 * Encodes a Cap'n Proto message payload into a framed packet.
 *
 * Frame format (capnp.md §2):
 *   [0xAA] [LEN: uint8] [PAYLOAD...] [CRC_LO] [CRC_HI]
 *
 * CRC16-CCITT covers LEN (1 byte) + PAYLOAD, transmitted little-endian.
 *
 * The board is the RFCOMM server and never expects a downlink in v1, so this
 * encoder exists for the in-process simulator (which round-trips real frames
 * through [FrameDecoder]) and for decoder self-tests.
 */
object FrameEncoder {

    fun encode(payload: ByteArray): ByteArray {
        val length = payload.size
        require(length in 1..FrameDecoder.MAX_PAYLOAD) {
            "Payload size $length out of range [1, ${FrameDecoder.MAX_PAYLOAD}]"
        }

        // Compute CRC over the length byte + payload.
        val crcInput = ByteArray(1 + length)
        crcInput[0] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, crcInput, 1, length)
        val crc = Crc16.compute(crcInput)

        // Assemble frame: SYNC(1) + LEN(1) + PAYLOAD(N) + CRC(2)
        val frame = ByteArray(1 + 1 + length + 2)
        frame[0] = FrameDecoder.SYNC_BYTE.toByte()
        frame[1] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 2, length)
        frame[2 + length] = (crc and 0xFF).toByte()
        frame[2 + length + 1] = ((crc shr 8) and 0xFF).toByte()

        return frame
    }
}
