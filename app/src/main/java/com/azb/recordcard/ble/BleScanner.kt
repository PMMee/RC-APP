package com.azb.recordcard.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.azb.recordcard.core.AppLog

data class ScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long,
)

/** BLE scanner; the controller applies the name filter. */
class BleScanner(
    context: Context,
    private val onDevice: (ScanDevice) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private var scanning = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (scanning) return true
        if (!hasScanPermission()) {
            onError("缺少蓝牙扫描权限")
            return false
        }
        val adapter = manager?.adapter
        if (adapter == null) {
            onError("此设备不支持蓝牙")
            return false
        }
        if (!adapter.isEnabled) {
            onError("蓝牙未开启，请先打开蓝牙")
            return false
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onError("无法获取蓝牙扫描器")
            return false
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, callback)
            scanning = true
            AppLog.i(TAG, "开始扫描")
            true
        } catch (e: SecurityException) {
            onError("缺少蓝牙扫描权限")
            false
        } catch (e: Exception) {
            onError("扫描启动失败: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        if (!hasScanPermission()) return
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(callback)
            AppLog.i(TAG, "停止扫描")
        } catch (_: Exception) {
        }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName
                ?: try {
                    device.name
                } catch (e: SecurityException) {
                    null
                }
                ?: return
            if (name.isBlank()) return
            onDevice(
                ScanDevice(
                    address = device.address,
                    name = name,
                    rssi = result.rssi,
                    lastSeen = System.currentTimeMillis(),
                ),
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            AppLog.e(TAG, "扫描失败: $errorCode")
            onError("扫描失败 (code=$errorCode)")
        }
    }

    companion object {
        private const val TAG = "Scan"
    }
}
