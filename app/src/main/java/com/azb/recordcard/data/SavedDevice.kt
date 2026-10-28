package com.azb.recordcard.data

/** A recording card remembered by the app. */
data class SavedDevice(
    val address: String,
    val name: String,
    val deviceId: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val wifiMac: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    val lastBatteryPct: Int? = null,
    val lastPendingFiles: Int? = null,
    val autoSync: Boolean = true,
    val preferHighSpeed: Boolean = false,
) {
    val displayName: String
        get() = name.ifBlank { address }

    fun mergedIdentity(
        deviceId: String?,
        serial: String?,
        firmware: String?,
        hardware: String?,
        wifiMac: String?,
    ): SavedDevice = copy(
        deviceId = deviceId ?: this.deviceId,
        serial = serial ?: this.serial,
        firmware = firmware ?: this.firmware,
        hardware = hardware ?: this.hardware,
        wifiMac = wifiMac ?: this.wifiMac,
    )
}

data class AppSettings(
    val autoSyncDefault: Boolean = true,
    val preferHighSpeedDefault: Boolean = false,
    val scanPrefix: String = "RecordingCard",
    val showAllDevices: Boolean = true,
    val keepScreenOn: Boolean = true,
)
