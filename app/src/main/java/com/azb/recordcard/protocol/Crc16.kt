package com.azb.recordcard.protocol

/**
 * CRC-16/XMODEM (poly 0x1021, init 0x0000), as used by the device transfer frame.
 */
object Crc16 {
    private val TABLE = IntArray(256) { index ->
        var crc = index shl 8
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
        crc
    }

    fun xmodem(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            crc = ((crc shl 8) and 0xFFFF) xor
                TABLE[((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc and 0xFFFF
    }
}
