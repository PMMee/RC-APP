package com.azb.recordcard.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import com.azb.recordcard.core.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WifiConnectResult {
    data class Connected(val network: Network) : WifiConnectResult
    data object PermissionMissing : WifiConnectResult
    data object Rejected : WifiConnectResult
    data object Timeout : WifiConnectResult
    data class Failed(val message: String) : WifiConnectResult
}

/**
 * Connects the phone to the card's SoftAP (open network) for high speed
 * HTTP transfer. Android shows its own confirmation dialog for the request.
 *
 * On several OEM ROMs (Huawei/EMUI in particular) this dialog is suppressed or
 * auto-rejected; the caller should fall back to asking the user to join the
 * hotspot manually.
 */
class WifiApConnector(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    suspend fun connect(ssid: String, timeoutMs: Long = 35_000): WifiConnectResult {
        release()
        val permission = requiredPermission()
        if (!hasPermission(permission)) {
            AppLog.e(TAG, "缺少 WiFi 权限: $permission")
            return WifiConnectResult.PermissionMissing
        }
        val manager = connectivity ?: return WifiConnectResult.Failed("系统不支持网络请求")
        val wifi = appContext.getSystemService(WifiManager::class.java)
        if (wifi != null && !wifi.isWifiEnabled) {
            AppLog.w(TAG, "WiFi 未开启")
            return WifiConnectResult.Failed("WiFi 未开启")
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val deferred = CompletableDeferred<WifiConnectResult>()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!deferred.isCompleted) {
                    AppLog.i(TAG, "系统已批准热点连接: $ssid")
                    deferred.complete(WifiConnectResult.Connected(network))
                }
            }

            override fun onUnavailable() {
                AppLog.w(TAG, "系统拒绝/取消热点连接: $ssid")
                if (!deferred.isCompleted) deferred.complete(WifiConnectResult.Rejected)
            }

            override fun onLost(network: Network) {
                AppLog.w(TAG, "热点网络丢失")
            }
        }
        callback = networkCallback
        return try {
            manager.requestNetwork(request, networkCallback, timeoutMs.toInt())
            val result = withTimeoutOrNull(timeoutMs + 3_000) { deferred.await() }
            when (result) {
                is WifiConnectResult.Connected -> {
                    network = result.network
                    AppLog.i(TAG, "已连接设备热点: $ssid")
                    result
                }

                null -> {
                    AppLog.w(TAG, "连接设备热点超时: $ssid")
                    WifiConnectResult.Timeout
                }

                else -> result
            }
        } catch (e: SecurityException) {
            AppLog.e(TAG, "连接热点缺少权限: ${e.message}")
            release()
            WifiConnectResult.Failed("缺少权限：${e.message}")
        } catch (e: Exception) {
            AppLog.e(TAG, "连接热点失败: ${e.message}")
            release()
            WifiConnectResult.Failed(e.message ?: "未知错误")
        }
    }

    fun release() {
        callback?.let {
            runCatching { connectivity?.unregisterNetworkCallback(it) }
        }
        callback = null
        network = null
    }

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "WiFi"
    }
}
