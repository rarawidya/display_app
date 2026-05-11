package com.example.displayapp.data.protocol

/**
 * Encodes a Cap'n Proto message payload into a framed packet.
 *
 * Frame format:
 *   [0xCA] [0xFE] [LEN_LO] [LEN_HI] [PAYLOAD...] [CRC_LO] [CRC_HI]
 *
 * CRC covers LENGTH (2 bytes) + PAYLOAD.
 */
object FrameEncoder {

    fun encode(payload: ByteArray): ByteArray {
        val length = payload.size
        require(length in 1..FrameDecoder.MAX_PAYLOAD) {
            "Payload size $length out of range [1, ${FrameDecoder.MAX_PAYLOAD}]"
        }

        // Compute CRC over length bytes + payload
        val crcInput = ByteArray(2 + length)
        crcInput[0] = (length and 0xFF).toByte()
        crcInput[1] = ((length shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, crcInput, 2, length)
        val crc = Crc16.compute(crcInput)

        // Assemble frame: SYNC(2) + LENGTH(2) + PAYLOAD(N) + CRC(2)
        val frame = ByteArray(2 + 2 + length + 2)
        frame[0] = FrameDecoder.SYNC_BYTE_HI.toByte()
        frame[1] = FrameDecoder.SYNC_BYTE_LO.toByte()
        frame[2] = (length and 0xFF).toByte()
        frame[3] = ((length shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 4, length)
        frame[4 + length] = (crc and 0xFF).toByte()
        frame[4 + length + 1] = ((crc shr 8) and 0xFF).toByte()

        return frame
    }
}
