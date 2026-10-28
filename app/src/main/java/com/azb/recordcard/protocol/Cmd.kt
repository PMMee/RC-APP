package com.azb.recordcard.protocol

import java.nio.charset.StandardCharsets

/** Command codes written to characteristic 0xA104 (Device Control). */
object Cmd {
    const val ENABLE_AP = 0x01
    const val DISABLE_AP = 0x02
    const val GET_WIFI_STATUS = 0x03

    const val FILE_REQUEST = 0x04
    const val TRANSFER_START = 0x05
    const val TRANSFER_STOP = 0x06
    const val TRANSFER_ACK = 0x07
    const val TRANSFER_RESUME = 0x08

    const val START_HS = 0x11
    const val STOP_HS = 0x12
    const val FILE_DONE = 0x13

    const val START_SYNC = 0x21
    const val STOP_SYNC = 0x22

    const val DELETE_FILE = 0x31
    const val FORMAT = 0x32
    const val GET_STORAGE = 0x33
    const val GET_ALL_FILES = 0x34
    const val MARK_PENDING = 0x35

    const val TIME_SYNC = 0x41

    const val AUDIO_STREAM_ON = 0x51
    const val AUDIO_STREAM_OFF = 0x52

    const val SET_REC_MODE = 0x61
    const val GET_REC_MODE = 0x62

    fun single(code: Int): ByteArray = byteArrayOf(code.toByte())

    fun withText(code: Int, text: String): ByteArray =
        byteArrayOf(code.toByte()) + text.toByteArray(StandardCharsets.UTF_8)

    /**
     * 流控 ACK（0x07）：载荷是**期望收到的下一个 DATA 帧序号**（小端 2B），
     * 不是"本窗口收到了几帧"。
     *
     * 设备侧契约见固件 `main/network/ble_cmd.h` 的 `kTransferAck = 0x07  // + 2B LE ack_seq`。
     * 当前固件仍不读这两个字节（只按"收到一个 0x07 = 放出 1 个窗口信用"处理），
     * 所以填错不会立刻坏；但只要设备改成按 ack_seq 精确释放信用，填窗口计数
     * （永远 1..12）就会卡死在第一个窗口。
     *
     * @param ackSeq 期望收到的下一个 DATA 帧序号 = (最后一帧 DATA 的 seq + 1) and 0xFFFF
     */
    fun transferAck(ackSeq: Int): ByteArray = byteArrayOf(
        TRANSFER_ACK.toByte(),
        (ackSeq and 0xFF).toByte(),
        ((ackSeq ushr 8) and 0xFF).toByte(),
    )

    fun timeSync(epochSeconds: Long, tzOffsetMinutes: Int): ByteArray =
        withText(TIME_SYNC, """{"timestamp":$epochSeconds,"tz_offset_min":$tzOffsetMinutes}""")

    fun setRecMode(mode: Int): ByteArray = byteArrayOf(SET_REC_MODE.toByte(), mode.toByte())
}
