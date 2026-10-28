package com.azb.recordcard.protocol

import org.json.JSONObject

sealed interface StatusMessage {
    data class Full(val status: DeviceStatus) : StatusMessage
    data class Reply(val reply: CmdReply) : StatusMessage
    data class Ap(val info: ApInfo) : StatusMessage
    data class StoragePatch(val storage: StorageInfo?) : StatusMessage
    data class RecModePatch(val mode: Int, val channels: Int, val sampleRate: Int) : StatusMessage
    data class Unknown(val text: String) : StatusMessage
}

/**
 * Parser for the JSON payloads pushed on 0xA105.
 *
 * The device sends either a full snapshot (contains recording / battery / transfer)
 * or a small single-purpose patch (cmd_rsp, ap_*, storage, rec_mode).
 */
object StatusParser {
    fun parse(text: String): StatusMessage? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return StatusMessage.Unknown(trimmed.take(160))

        if (obj.has("cmd_rsp")) {
            val reply = obj.optJSONObject("cmd_rsp")
                ?: return StatusMessage.Reply(CmdReply(ok = true, message = trimmed))
            return StatusMessage.Reply(
                CmdReply(
                    ok = reply.optBoolean("ok", false),
                    message = reply.optString("msg", ""),
                ),
            )
        }
        if (obj.has("recording") || obj.has("pending_files") || obj.has("transfer")) {
            return StatusMessage.Full(parseFull(obj))
        }
        if (obj.has("ap_enabled")) {
            return StatusMessage.Ap(
                ApInfo(
                    enabled = obj.optBoolean("ap_enabled", false),
                    ssid = obj.optString("ap_ssid").ifBlank { null },
                    ip = obj.optString("ap_ip").ifBlank { null },
                ),
            )
        }
        if (obj.has("storage")) {
            return StatusMessage.StoragePatch(parseStorage(obj))
        }
        if (obj.has("rec_mode")) {
            return StatusMessage.RecModePatch(
                mode = obj.optInt("rec_mode", 0),
                channels = obj.optInt("rec_ch", 0),
                sampleRate = obj.optInt("rec_sr", 0),
            )
        }
        return StatusMessage.Unknown(trimmed.take(160))
    }

    fun parseFull(obj: JSONObject): DeviceStatus {
        val battery = obj.optJSONObject("battery")?.let {
            BatteryInfo(
                voltageMv = it.optInt("voltage_mv", 0),
                socPct = it.optInt("soc_pct", 0),
                tempC = it.optInt("temp_c", 0),
                charging = it.optBoolean("charging", false),
                low = it.optBoolean("low", false),
            )
        }
        val storage = if (obj.isNull("storage")) null else obj.optJSONObject("storage")?.let {
            StorageInfo(
                totalKb = it.optLong("total_kb", 0),
                freeKb = it.optLong("free_kb", 0),
            )
        }
        val transfer = obj.optJSONObject("transfer")?.let {
            TransferInfo(
                active = it.optBoolean("active", false),
                type = it.optString("type", ""),
                fileId = it.optString("file_id", ""),
                fileIndex = it.optInt("file_idx", 0),
                fileCount = it.optInt("file_cnt", 0),
                bytesDone = it.optLong("bytes_done", 0),
                bytesTotal = it.optLong("bytes_total", 0),
            )
        } ?: TransferInfo()

        return DeviceStatus(
            recording = obj.optBoolean("recording", false),
            recordingPaused = obj.optBoolean("recording_paused", false),
            recordingSeconds = obj.optInt("recording_s", 0),
            recMode = obj.optInt("rec_mode", 1),
            recChannels = obj.optInt("rec_ch", 2),
            recSampleRate = obj.optInt("rec_sr", 16_000),
            bleConnected = obj.optBoolean("ble_conn", false),
            wifiAp = obj.optBoolean("wifi_ap", false),
            ota = obj.optString("ota", "idle"),
            battery = battery,
            storage = storage,
            pendingFiles = obj.optInt("pending_files", 0),
            pendingBytes = obj.optLong("pending_bytes", 0),
            transfer = transfer,
        )
    }

    fun parseStorage(obj: JSONObject): StorageInfo? {
        val storage = obj.optJSONObject("storage") ?: return null
        return StorageInfo(
            totalKb = storage.optLong("total_kb", 0),
            freeKb = storage.optLong("free_kb", 0),
        )
    }
}
