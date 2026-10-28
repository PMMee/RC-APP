package com.azb.recordcard.data

import android.content.Context
import com.azb.recordcard.core.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for paired cards and global settings.
 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun loadDevices(): List<SavedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        SavedDevice(
                            address = o.optString("address"),
                            name = o.optString("name"),
                            deviceId = o.optString("deviceId").ifBlank { null },
                            serial = o.optString("serial").ifBlank { null },
                            firmware = o.optString("firmware").ifBlank { null },
                            hardware = o.optString("hardware").ifBlank { null },
                            wifiMac = o.optString("wifiMac").ifBlank { null },
                            addedAt = o.optLong("addedAt", 0L),
                            lastConnectedAt = o.optLong("lastConnectedAt", 0L).takeIf { it > 0 },
                            lastBatteryPct = o.optInt("lastBatteryPct", -1).takeIf { it >= 0 },
                            lastPendingFiles = o.optInt("lastPendingFiles", -1).takeIf { it >= 0 },
                            autoSync = o.optBoolean("autoSync", true),
                            preferHighSpeed = o.optBoolean("preferHighSpeed", false),
                        ),
                    )
                }
            }
        }.onFailure { AppLog.e(TAG, "读取设备列表失败: ${it.message}") }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveDevices(devices: List<SavedDevice>) {
        val array = JSONArray()
        devices.forEach { d ->
            array.put(
                JSONObject().apply {
                    put("address", d.address)
                    put("name", d.name)
                    put("deviceId", d.deviceId ?: "")
                    put("serial", d.serial ?: "")
                    put("firmware", d.firmware ?: "")
                    put("hardware", d.hardware ?: "")
                    put("wifiMac", d.wifiMac ?: "")
                    put("addedAt", d.addedAt)
                    put("lastConnectedAt", d.lastConnectedAt ?: 0L)
                    put("lastBatteryPct", d.lastBatteryPct ?: -1)
                    put("lastPendingFiles", d.lastPendingFiles ?: -1)
                    put("autoSync", d.autoSync)
                    put("preferHighSpeed", d.preferHighSpeed)
                },
            )
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    @Synchronized
    fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                autoSyncDefault = o.optBoolean("autoSyncDefault", true),
                preferHighSpeedDefault = o.optBoolean("preferHighSpeedDefault", false),
                scanPrefix = o.optString("scanPrefix", "RecordingCard").ifBlank { "RecordingCard" },
                showAllDevices = o.optBoolean("showAllDevices", true),
                keepScreenOn = o.optBoolean("keepScreenOn", true),
            )
        }.getOrDefault(AppSettings())
    }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        val o = JSONObject().apply {
            put("autoSyncDefault", settings.autoSyncDefault)
            put("preferHighSpeedDefault", settings.preferHighSpeedDefault)
            put("scanPrefix", settings.scanPrefix)
            put("showAllDevices", settings.showAllDevices)
            put("keepScreenOn", settings.keepScreenOn)
        }
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    /** Records the last connection metadata for a device, if it is known. */
    @Synchronized
    fun updateDevice(address: String, transform: (SavedDevice) -> SavedDevice) {
        val devices = loadDevices().toMutableList()
        val index = devices.indexOfFirst { it.address == address }
        if (index < 0) return
        devices[index] = transform(devices[index])
        saveDevices(devices)
    }

    companion object {
        private const val TAG = "DeviceStore"
        private const val PREFS = "recordcard_store"
        private const val KEY_DEVICES = "devices"
        private const val KEY_SETTINGS = "settings"
    }
}
