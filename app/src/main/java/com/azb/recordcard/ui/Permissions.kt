package com.azb.recordcard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

data class BlePermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

fun blePermissionNames(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberBlePermissionState(onGranted: () -> Unit = {}): BlePermissionState {
    val context = LocalContext.current
    val names = remember { blePermissionNames() }
    var granted by remember {
        mutableStateOf(names.all { isPermissionGranted(context, it) })
    }
    val currentOnGranted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = names.all { name ->
            result[name] == true || isPermissionGranted(context, name)
        }
        if (granted) currentOnGranted()
    }
    return BlePermissionState(granted = granted) { launcher.launch(names) }
}

fun needsNearbyWifiPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun hasNearbyWifiPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        isPermissionGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)

/**
 * Permissions needed before asking the system to join the card's SoftAP.
 * Android 10-12 still gates network requests behind location; Android 13+
 * replaced that with NEARBY_WIFI_DEVICES.
 */
fun wifiPermissionNames(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasWifiPermissions(context: Context): Boolean =
    wifiPermissionNames().all { isPermissionGranted(context, it) }

fun isLocationServiceEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    val manager = context.getSystemService(android.location.LocationManager::class.java)
        ?: return true
    return manager.isLocationEnabled
}

fun needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun hasNotificationPermission(context: Context): Boolean =
    !needsNotificationPermission() ||
        isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)

/**
 * Android 17 (API 37) blocks local-network access for apps targeting 37 unless
 * ACCESS_LOCAL_NETWORK is granted at runtime. Without it sockets to
 * 192.168.4.1 are silently dropped (timeout/ENONET), while the browser works.
 */
fun needsLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT >= 37

fun hasLocalNetworkPermission(context: Context): Boolean =
    !needsLocalNetworkPermission() ||
        isPermissionGranted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)

fun localNetworkPermissionName(): String = Manifest.permission.ACCESS_LOCAL_NETWORK
