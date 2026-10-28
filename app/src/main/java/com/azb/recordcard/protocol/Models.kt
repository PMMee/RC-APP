package com.azb.recordcard.protocol

/** One recording stored on the device. */
data class RemoteFile(
    val id: String,
    val size: Long,
    val uploaded: Boolean,
)

data class BatteryInfo(
    val voltageMv: Int = 0,
    val socPct: Int = 0,
    val tempC: Int = 0,
    val charging: Boolean = false,
    val low: Boolean = false,
)

data class StorageInfo(
    val totalKb: Long = 0,
    val freeKb: Long = 0,
) {
    val totalBytes: Long get() = totalKb * 1024
    val freeBytes: Long get() = freeKb * 1024
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class TransferInfo(
    val active: Boolean = false,
    val type: String = "",
    val fileId: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

data class DeviceStatus(
    val recording: Boolean = false,
    val recordingPaused: Boolean = false,
    val recordingSeconds: Int = 0,
    val recMode: Int = 1,
    val recChannels: Int = 2,
    val recSampleRate: Int = 16_000,
    val bleConnected: Boolean = true,
    val wifiAp: Boolean = false,
    val ota: String = "idle",
    val battery: BatteryInfo? = null,
    val storage: StorageInfo? = null,
    val pendingFiles: Int = 0,
    val pendingBytes: Long = 0,
    val transfer: TransferInfo = TransferInfo(),
)

data class Identity(
    val deviceId: String? = null,
    val deviceKey: String? = null,
    val wifiMac: String? = null,
    val serial: String? = null,
    /** 应用版本，来自 0x2A28（Software Revision）；0x2A26 报的是 IDF 版本，不可用于门控。 */
    val firmware: String? = null,
    val hardware: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
)

data class ApInfo(
    val enabled: Boolean = false,
    val ssid: String? = null,
    val ip: String? = null,
)

data class CmdReply(
    val ok: Boolean,
    val message: String,
)

/** Recording mode table from protocol v4 section 2.3.8. */
enum class RecMode(val value: Int, val label: String, val channels: Int, val sampleRate: Int) {
    OPUS(0x00, "Opus 4ch 16k（不可用）", 4, 16_000),
    ADPCM_2CH_16K(0x01, "ADPCM 2ch 16k", 2, 16_000),
    RAW_4CH_16K(0x02, "RAW 4ch 16k", 4, 16_000),
    ADPCM_1CH_16K(0x03, "ADPCM 1ch 16k", 1, 16_000),
    ADPCM_2CH_8K(0x04, "ADPCM 2ch 8k", 2, 8_000),
    ADPCM_1CH_8K(0x05, "ADPCM 1ch 8k", 1, 8_000),
    RAW_2CH_16K(0x06, "RAW 2ch 16k", 2, 16_000),
    RAW_4CH_8K(0x07, "RAW 4ch 8k", 4, 8_000),
    RAW_1CH_16K(0x08, "RAW 1ch 16k", 1, 16_000);

    companion object {
        fun fromValue(value: Int): RecMode? = entries.firstOrNull { it.value == value }
    }
}
