package com.azb.recordcard.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.app.ConnPhase
import com.azb.recordcard.ble.ScanDevice
import com.azb.recordcard.ui.components.BackHeader
import com.azb.recordcard.ui.components.Badge
import com.azb.recordcard.ui.components.DeviceAvatar
import com.azb.recordcard.ui.components.EmptyState
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.PermissionCard
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.components.SignalBars
import com.azb.recordcard.ui.rememberBlePermissionState

@Composable
fun ScanScreen(
    controller: AppController,
    onBack: () -> Unit,
    onConnected: (String) -> Unit,
) {
    val scan by controller.scan.collectAsState()
    val settings by controller.settings.collectAsState()
    val session by controller.session.collectAsState()
    var onlyCards by rememberSaveable { mutableStateOf(true) }
    var pendingAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        controller.startScan()
    }

    fun requestEnableBluetooth() {
        runCatching {
            enableBluetoothLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
            )
        }.onFailure {
            controller.notify("无法打开蓝牙设置：${it.message}")
        }
    }

    val bluetoothDisabled = scan.error?.contains("蓝牙未开启") == true

    val permission = rememberBlePermissionState(onGranted = { controller.startScan() })

    LaunchedEffect(Unit) {
        if (permission.granted) {
            controller.startScan()
        } else {
            permission.request()
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.stopScan() }
    }

    LaunchedEffect(session?.address, session?.phase) {
        val target = pendingAddress ?: return@LaunchedEffect
        val current = session ?: return@LaunchedEffect
        if (current.address != target) return@LaunchedEffect
        when (current.phase) {
            ConnPhase.READY -> {
                pendingAddress = null
                onConnected(target)
            }

            ConnPhase.FAILED, ConnPhase.DISCONNECTED -> pendingAddress = null
            else -> Unit
        }
    }

    val prefix = settings.scanPrefix
    val cardDevices = scan.devices.filter { device ->
        device.name.contains(prefix, ignoreCase = true) ||
            device.name.contains("recording", ignoreCase = true)
    }
    val visible = if (onlyCards) cardDevices else scan.devices

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            BackHeader(
                title = "添加录音卡",
                subtitle = "保持录音卡开机并靠近手机",
                onBack = onBack,
            ) {
                IconButton(onClick = {
                    if (scan.running) {
                        controller.stopScan()
                    } else {
                        controller.startScan()
                    }
                }) {
                    GlyphIcon(
                        if (scan.running) Glyph.CLOSE else Glyph.REFRESH,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!permission.granted) {
            item {
                PermissionCard(
                    title = "需要蓝牙权限",
                    message = "扫描附近设备需要「附近的设备」权限。",
                    actionLabel = "授权并扫描",
                    onAction = permission.request,
                )
            }
        }

        item { ScanStatusCard(running = scan.running, count = cardDevices.size, error = scan.error) }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅显示录音卡", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "匹配名称前缀「$prefix-」",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onlyCards, onCheckedChange = { onlyCards = it })
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyState(
                    glyph = if (bluetoothDisabled) Glyph.BLUETOOTH else Glyph.SEARCH,
                    title = when {
                        bluetoothDisabled -> "蓝牙未开启"
                        scan.running -> "正在搜索附近设备…"
                        else -> "没有发现设备"
                    },
                    description = when {
                        scan.error != null -> scan.error.orEmpty()
                        onlyCards && scan.devices.isNotEmpty() ->
                            "发现了 ${scan.devices.size} 台其他蓝牙设备，可关闭「仅显示录音卡」查看。"
                        else -> "确认录音卡已开机、未被其他手机连接，并靠近手机后重试。Android 11 及以下还需开启系统定位服务。"
                    },
                    actionLabel = when {
                        bluetoothDisabled -> "打开蓝牙"
                        scan.running -> null
                        else -> "重新扫描"
                    },
                    onAction = when {
                        bluetoothDisabled -> ({ requestEnableBluetooth() })
                        scan.running -> null
                        else -> ({ controller.startScan() })
                    },
                )
            }
        } else {
            items(visible, key = { it.address }) { device ->
                val isPending = pendingAddress == device.address
                val connecting = isPending &&
                    session?.let { current ->
                        current.address == device.address &&
                            (current.phase == ConnPhase.CONNECTING ||
                                current.phase == ConnPhase.DISCOVERING)
                    } == true
                ScanDeviceCard(
                    device = device,
                    matched = cardDevices.any { it.address == device.address },
                    connecting = connecting,
                    onClick = {
                        if (!permission.granted) {
                            permission.request()
                        } else {
                            pendingAddress = device.address
                            controller.addDeviceAndConnect(device.name, device.address)
                        }
                    },
                )
            }
        }

        if (visible.isNotEmpty() && scan.running) {
            item {
                Text(
                    text = "正在持续扫描… 点击设备即可连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanStatusCard(running: Boolean, count: Int, error: String?) {
    val transition = rememberInfiniteTransition(label = "scan-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val dotColor by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "dot",
    )

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = if (running) pulse else 0.6f)),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "正在扫描" else "扫描已停止",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (running) "已发现 $count 台录音卡" else {
                        error ?: "点击右上角刷新重新扫描"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!running && error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun ScanDeviceCard(
    device: ScanDevice,
    matched: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    SectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = !connecting, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(size = 44, glyph = Glyph.BLUETOOTH)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (matched) {
                        Spacer(Modifier.width(6.dp))
                        Badge("录音卡", MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SignalBars(device.rssi)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
