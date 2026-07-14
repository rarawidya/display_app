package com.innodrive.evdash.data.protocol

/**
 * CRC-16/CCITT-FALSE (polynomial 0x1021, init 0xFFFF, no final XOR).
 * Compatible with most embedded CRC libraries using the same parameters.
 */
object Crc16 {

    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var crc = i shl 8
            for (bit in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
            table[i] = crc and 0xFFFF
        }
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val index = ((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor TABLE[index]) and 0xFFFF
        }
        return crc
    }
}
