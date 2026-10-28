package com.azb.recordcard.protocol

import org.json.JSONArray

data class FileListResult(
    val files: List<RemoteFile>,
    val isFullList: Boolean,
)

/**
 * 0xA102 pushes the JSON array in MTU-sized chunks with a 20 ms gap.
 * Chunks have no framing, so completeness is detected by parsing the
 * accumulated buffer as JSON.
 */
class FileListAssembler {
    private val buffer = StringBuilder()

    fun reset() {
        buffer.setLength(0)
    }

    fun append(chunk: String): FileListResult? {
        if (chunk.isEmpty()) return null
        buffer.append(chunk)
        if (buffer.length > MAX_BUFFER) {
            buffer.setLength(0)
            return null
        }
        val text = buffer.toString().trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return null
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return null
        buffer.setLength(0)
        return parseArray(array)
    }

    private fun parseArray(array: JSONArray): FileListResult {
        val files = ArrayList<RemoteFile>(array.length())
        var isFull = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.has("uploaded")) isFull = true
            files += RemoteFile(
                id = item.optString("id", ""),
                size = item.optLong("size", 0),
                uploaded = item.optBoolean("uploaded", false),
            )
        }
        return FileListResult(files, isFull)
    }

    companion object {
        private const val MAX_BUFFER = 512 * 1024

        fun parse(json: String): FileListResult? {
            val array = runCatching { JSONArray(json.trim()) }.getOrNull() ?: return null
            val files = ArrayList<RemoteFile>(array.length())
            var isFull = false
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.has("uploaded")) isFull = true
                files += RemoteFile(
                    id = item.optString("id", ""),
                    size = item.optLong("size", 0),
                    uploaded = item.optBoolean("uploaded", false),
                )
            }
            return FileListResult(files, isFull)
        }
    }
}
