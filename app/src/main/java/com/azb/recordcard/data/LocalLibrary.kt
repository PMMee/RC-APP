package com.azb.recordcard.data

import android.content.Context
import android.os.Environment
import com.azb.recordcard.audio.WavInfo
import com.azb.recordcard.audio.WavReader
import com.azb.recordcard.core.AppLog
import org.json.JSONObject
import java.io.File

data class LocalRecording(
    val id: String,
    val file: File,
    val size: Long,
    val durationMs: Long,
    val savedAt: Long,
    val deviceAddress: String?,
    val deviceName: String?,
    val formatLabel: String,
    val channels: Int,
    val sampleRate: Int,
) {
    val extension: String get() = file.extension.lowercase()
}

/**
 * Recordings downloaded from cards are stored in the app-specific external
 * music directory so they survive app updates and are easy to inspect.
 */
class LocalLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val root: File = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: appContext.filesDir,
        "RecordCard",
    )
    val partialDir: File = File(root, ".partial")

    private val infoCache = HashMap<String, Pair<Long, WavInfo?>>()

    fun ensureDirectories() {
        if (!root.exists()) root.mkdirs()
        if (!partialDir.exists()) partialDir.mkdirs()
    }

    fun list(): List<LocalRecording> {
        ensureDirectories()
        val meta = loadMeta()
        val records = ArrayList<LocalRecording>()
        root.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val ext = file.extension.lowercase()
            if (ext != "wav" && ext != "ogg") return@forEach
            val id = file.nameWithoutExtension
            val info = readInfo(file)
            val entry = meta.optJSONObject(file.name)
            records += LocalRecording(
                id = id,
                file = file,
                size = file.length(),
                durationMs = info?.durationMs ?: 0L,
                savedAt = entry?.optLong("savedAt", 0L)?.takeIf { it > 0 } ?: file.lastModified(),
                deviceAddress = entry?.optString("deviceAddress")?.ifBlank { null },
                deviceName = entry?.optString("deviceName")?.ifBlank { null },
                formatLabel = info?.formatLabel ?: ext.uppercase(),
                channels = info?.channels ?: 0,
                sampleRate = info?.sampleRate ?: 0,
            )
        }
        return records.sortedByDescending { it.savedAt }
    }

    fun partialFile(id: String, kind: String = "ble"): File {
        ensureDirectories()
        return File(partialDir, "$id.$kind.part")
    }

    fun commit(
        temp: File,
        id: String,
        ext: String,
        deviceAddress: String?,
        deviceName: String?,
    ): LocalRecording? {
        ensureDirectories()
        if (!temp.isFile || temp.length() <= 0) return null
        val target = File(root, "$id.${ext.lowercase()}")
        if (target.exists()) target.delete()
        val moved = temp.renameTo(target) || runCatching {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)
        if (!moved) {
            AppLog.e(TAG, "保存录音失败: ${temp.name} -> ${target.name}")
            return null
        }
        updateMeta(target.name, deviceAddress, deviceName)
        val info = readInfo(target)
        return LocalRecording(
            id = id,
            file = target,
            size = target.length(),
            durationMs = info?.durationMs ?: 0L,
            savedAt = System.currentTimeMillis(),
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            formatLabel = info?.formatLabel ?: ext.uppercase(),
            channels = info?.channels ?: 0,
            sampleRate = info?.sampleRate ?: 0,
        )
    }

    fun delete(recording: LocalRecording): Boolean {
        val ok = recording.file.delete()
        if (ok) {
            infoCache.remove(recording.file.absolutePath)
            removeMeta(recording.file.name)
        }
        return ok
    }

    fun cleanupPartials(olderThanMs: Long) {
        ensureDirectories()
        val cutoff = System.currentTimeMillis() - olderThanMs
        partialDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun readInfo(file: File): WavInfo? {
        val key = file.absolutePath
        val cached = infoCache[key]
        if (cached != null && cached.first == file.lastModified()) return cached.second
        val info = WavReader.read(file)
        infoCache[key] = file.lastModified() to info
        return info
    }

    private fun loadMeta(): JSONObject {
        val raw = prefs.getString(KEY_META, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun updateMeta(fileName: String, deviceAddress: String?, deviceName: String?) {
        val meta = loadMeta()
        meta.put(
            fileName,
            JSONObject().apply {
                put("savedAt", System.currentTimeMillis())
                put("deviceAddress", deviceAddress ?: "")
                put("deviceName", deviceName ?: "")
            },
        )
        prefs.edit().putString(KEY_META, meta.toString()).apply()
    }

    private fun removeMeta(fileName: String) {
        val meta = loadMeta()
        if (meta.has(fileName)) {
            meta.remove(fileName)
            prefs.edit().putString(KEY_META, meta.toString()).apply()
        }
    }

    companion object {
        private const val TAG = "LocalLibrary"
        private const val PREFS = "recordcard_library"
        private const val KEY_META = "meta"
    }
}
