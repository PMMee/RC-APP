package com.azb.recordcard.protocol

/**
 * Frame layout on characteristic 0xA103 (device -> phone):
 *
 * ```
 *  SeqH SeqL Cmd PaySzH PaySzL [payload] CRCH CRCL
 * ```
 * Seq / PaySz are big endian; CRC-16/XMODEM covers cmd + paySz + payload.
 */
data class TransferFrame(
    val seq: Int,
    val cmd: Int,
    val payload: ByteArray,
    val crcValid: Boolean,
) {
    val isData: Boolean get() = cmd == TransferFrameCodec.CMD_DATA
    val isFileStart: Boolean get() = cmd == TransferFrameCodec.CMD_FILE_START
    val isFileEnd: Boolean get() = cmd == TransferFrameCodec.CMD_FILE_END
}

object TransferFrameCodec {
    const val HEADER_SIZE = 5
    const val CRC_SIZE = 2
    const val CMD_DATA = 0x00
    const val CMD_FILE_START = 0x01
    const val CMD_FILE_END = 0x02
    const val CMD_ERROR = 0x03

    fun decode(data: ByteArray): TransferFrame? {
        if (data.size < HEADER_SIZE + CRC_SIZE) return null
        val seq = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val cmd = data[2].toInt() and 0xFF
        val paySz = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (HEADER_SIZE + paySz + CRC_SIZE > data.size) return null
        val payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + paySz)
        val expected = ((data[HEADER_SIZE + paySz].toInt() and 0xFF) shl 8) or
            (data[HEADER_SIZE + paySz + 1].toInt() and 0xFF)
        val actual = Crc16.xmodem(data, 2, 3 + paySz)
        return TransferFrame(seq, cmd, payload, expected == actual)
    }

    fun encode(seq: Int, cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        out[0] = ((seq ushr 8) and 0xFF).toByte()
        out[1] = (seq and 0xFF).toByte()
        out[2] = cmd.toByte()
        out[3] = ((payload.size ushr 8) and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        val crc = Crc16.xmodem(out, 2, 3 + payload.size)
        out[HEADER_SIZE + payload.size] = ((crc ushr 8) and 0xFF).toByte()
        out[HEADER_SIZE + payload.size + 1] = (crc and 0xFF).toByte()
        return out
    }
}
