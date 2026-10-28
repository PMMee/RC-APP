package com.azb.recordcard.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import com.azb.recordcard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Low-level network probe used when the card's HTTP endpoint is unreachable
 * from the app while the phone browser can open it.
 *
 * Results are written to files/diagnostics.txt so they can be pulled with
 * `adb shell run-as com.azb.recordcard cat files/diagnostics.txt`.
 */
object NetworkDiagnostics {
    suspend fun run(context: Context, host: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        sb.appendLine("app=${BuildConfig.VERSION_NAME} uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}")
        sb.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} host=$host")
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // A stale process-wide binding makes every new socket fail with ENONET.
        runCatching { manager?.bindProcessToNetwork(null) }
        runCatching {
            val proxies = ProxySelector.getDefault()?.select(URI("http://$host/api/files"))
            sb.appendLine("proxy=$proxies httpProxyHost=${System.getProperty("http.proxyHost")}")
        }.onFailure { sb.appendLine("proxyErr=${it.message}") }

        sb.appendLine("defaultSocket=${testSocket(null, host)}")
        sb.appendLine("defaultHttp=${testHttp(null, host)}")

        val networks = manager?.allNetworks ?: emptyArray()
        var wifiIndex = 0
        for (network in networks) {
            val caps = manager?.getNetworkCapabilities(network)
            val link = manager?.getLinkProperties(network)
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (!wifi) continue
            wifiIndex++
            sb.appendLine(
                "wifi#$wifiIndex net=$network " +
                    "internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                    "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "notRestricted=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)}",
            )
            sb.appendLine("  addrs=${link?.linkAddresses?.joinToString()}")
            sb.appendLine("  routes=${link?.routes?.joinToString()}")
            sb.appendLine("  socket=${testSocket(network, host)}")
            sb.appendLine("  http=${testHttp(network, host)}")
        }
        if (wifiIndex == 0) sb.appendLine("wifiNetworks=0")

        val report = sb.toString()
        runCatching { File(context.filesDir, "diagnostics.txt").writeText(report) }
        AppLog.i("Diag", report)
        report
    }

    private fun testSocket(network: Network?, host: String): String {
        val socket = Socket()
        return try {
            if (network != null) network.bindSocket(socket)
            socket.connect(InetSocketAddress(host, 80), 3_000)
            "OK local=${socket.localAddress.hostAddress}:${socket.localPort}"
        } catch (e: Exception) {
            "FAIL ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun testHttp(network: Network?, host: String): String {
        return try {
            val url = URL("http://$host/api/files")
            val connection = (if (network != null) {
                network.openConnection(url)
            } else {
                url.openConnection()
            }) as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText().take(120) }
            } else {
                ""
            }
            connection.disconnect()
            "HTTP $code $body"
        } catch (e: Exception) {
            "FAIL ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
