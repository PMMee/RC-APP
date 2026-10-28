package com.azb.recordcard.app

import com.azb.recordcard.ble.ScanDevice
import com.azb.recordcard.protocol.ApInfo
import com.azb.recordcard.protocol.CmdReply
import com.azb.recordcard.protocol.DeviceStatus
import com.azb.recordcard.protocol.Identity
import com.azb.recordcard.protocol.RemoteFile

enum class ConnPhase { DISCONNECTED, CONNECTING, DISCOVERING, READY, FAILED }

enum class TransferMode(val label: String) {
    BLE("蓝牙"),
    WIFI("高速 WiFi"),
}

data class PartialTransfer(
    val fileId: String,
    val bytes: Long,
)

data class TransferView(
    val mode: TransferMode,
    val fileId: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val speedBps: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val message: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

data class SessionState(
    val address: String,
    val name: String,
    val phase: ConnPhase = ConnPhase.DISCONNECTED,
    val note: String? = null,
    val mtu: Int = 0,
    val status: DeviceStatus? = null,
    val identity: Identity = Identity(),
    val pendingFiles: List<RemoteFile> = emptyList(),
    val allFiles: List<RemoteFile> = emptyList(),
    val allFilesLoaded: Boolean = false,
    val apInfo: ApInfo = ApInfo(),
    val transfer: TransferView? = null,
    val rssi: Int? = null,
    val lastReply: CmdReply? = null,
    val pendingResume: PartialTransfer? = null,
    val demo: Boolean = false,
    val updatedAt: Long = 0,
) {
    val isReady: Boolean get() = phase == ConnPhase.READY
    val pendingBytes: Long get() = pendingFiles.sumOf { it.size }
}

data class ScanState(
    val running: Boolean = false,
    val devices: List<ScanDevice> = emptyList(),
    val error: String? = null,
)

enum class ApPhase { IDLE, ENABLING, WAITING_WIFI, WAITING_MANUAL, LISTING, DOWNLOADING, DONE, ERROR }

data class ApState(
    val phase: ApPhase = ApPhase.IDLE,
    val ssid: String? = null,
    val ip: String? = null,
    val fileId: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val speedBps: Long = 0,
    val message: String? = null,
    val startedAt: Long = 0,
) {
    val running: Boolean
        get() = phase == ApPhase.ENABLING || phase == ApPhase.WAITING_WIFI ||
            phase == ApPhase.WAITING_MANUAL || phase == ApPhase.LISTING ||
            phase == ApPhase.DOWNLOADING

    val fraction: Float
        get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}
