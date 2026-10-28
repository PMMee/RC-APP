package com.azb.recordcard.wifi

import android.net.Network
import com.azb.recordcard.protocol.RemoteFile
import com.azb.recordcard.core.AppLog
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP endpoints exposed by the card while its SoftAP is running:
 * `GET /api/files`, `GET /api/file?id=`, `GET /api/mark?id=`.
 */
class DeviceHttpClient(
    private val boundNetwork: Network?,
    private val host: String,
) {
    @Volatile
    private var useBoundNetwork = false

    val hasBoundNetwork: Boolean get() = boundNetwork != null

    /**
     * Some OEM dual-WLAN stacks produce a `Network` from WifiNetworkSpecifier
     * whose sockets cannot reach the AP gateway, while the process default
     * network can. Let the caller switch between the two.
     */
    fun setBoundMode(enabled: Boolean) {
        useBoundNetwork = enabled && boundNetwork != null
    }

    fun modeLabel(): String = if (useBoundNetwork) "绑定网络" else "默认网络"

    fun listFiles(): List<RemoteFile> {
        val text = getText("http://$host/api/files")
        val array = JSONArray(text)
        val files = ArrayList<RemoteFile>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            files += RemoteFile(
                id = item.optString("id", ""),
                size = item.optLong("size", 0),
                uploaded = false,
            )
        }
        return files
    }

    /**
     * Streams the file to [target]. Returns the file extension deduced from
     * the response headers (defaults to wav).
     */
    fun download(
        id: String,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ): String {
        target.parentFile?.mkdirs()
        val connection = open("http://$host/api/file?id=${encode(id)}")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            var done = 0L
            target.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 200) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, if (total > 0) total else done)
            AppLog.i(TAG, "HTTP 下载完成: $id $done B")
            return extensionOf(connection, id)
        } finally {
            connection.disconnect()
        }
    }

    fun markUploaded(id: String): Boolean {
        return try {
            val text = getText("http://$host/api/mark?id=${encode(id)}")
            AppLog.i(TAG, "HTTP mark $id -> $text")
            text.contains("\"ok\":true") || text.contains("\"ok\": true")
        } catch (e: Exception) {
            AppLog.w(TAG, "HTTP mark 失败: ${e.message}")
            false
        }
    }

    private fun extensionOf(connection: HttpURLConnection, id: String): String {
        val disposition = connection.getHeaderField("Content-Disposition") ?: ""
        val name = Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.getOrNull(1)
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        if (!ext.isNullOrBlank() && ext.length <= 4) return ext
        return when {
            connection.contentType?.contains("ogg", ignoreCase = true) == true -> "ogg"
            id.endsWith(".ogg", ignoreCase = true) -> "ogg"
            else -> "wav"
        }
    }

    private fun getText(url: String): String {
        val connection = open(url)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = if (useBoundNetwork && boundNetwork != null) {
            boundNetwork.openConnection(URL(url)) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        connection.connectTimeout = 5_000
        connection.readTimeout = 120_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "*/*")
        return connection
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "HTTP"
    }
}
