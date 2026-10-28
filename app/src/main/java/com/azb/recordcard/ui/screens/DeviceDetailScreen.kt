package com.azb.recordcard.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.ApPhase
import com.azb.recordcard.app.ApState
import com.azb.recordcard.app.AppController
import com.azb.recordcard.app.ConnPhase
import com.azb.recordcard.app.SessionState
import com.azb.recordcard.app.TransferMode
import com.azb.recordcard.core.NetworkDiagnostics
import com.azb.recordcard.data.SavedDevice
import com.azb.recordcard.protocol.RecMode
import com.azb.recordcard.protocol.RemoteFile
import com.azb.recordcard.ui.components.ActionTile
import com.azb.recordcard.ui.components.BackHeader
import com.azb.recordcard.ui.components.Badge
import com.azb.recordcard.ui.components.ConfirmDialog
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.InfoRow
import com.azb.recordcard.ui.components.MetricTile
import com.azb.recordcard.ui.components.ProgressTrack
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.components.SignalBars
import com.azb.recordcard.ui.components.StatusChip
import com.azb.recordcard.ui.components.ThinDivider
import com.azb.recordcard.ui.components.PermissionCard
import com.azb.recordcard.ui.formatBytes
import com.azb.recordcard.ui.formatDuration
import com.azb.recordcard.ui.formatRecordingId
import com.azb.recordcard.ui.formatSpeed
import com.azb.recordcard.ui.hasWifiPermissions
import com.azb.recordcard.ui.hasLocalNetworkPermission
import com.azb.recordcard.ui.isLocationServiceEnabled
import com.azb.recordcard.ui.needsLocalNetworkPermission
import com.azb.recordcard.ui.relativeTime
import com.azb.recordcard.ui.rememberBlePermissionState
import com.azb.recordcard.ui.wifiPermissionNames
import com.azb.recordcard.ui.theme.SuccessGreen
import com.azb.recordcard.ui.theme.Teal200
import com.azb.recordcard.ui.theme.Teal700
import com.azb.recordcard.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceDetailScreen(
    controller: AppController,
    address: String,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTransferSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val devices by controller.devices.collectAsState()
    val session by controller.session.collectAsState()
    val ap by controller.ap.collectAsState()
    val device = devices.firstOrNull { it.address == address }
    val s = session?.takeIf { it.address == address }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticsRunning by remember { mutableStateOf(false) }
    val diagnosticsScope = rememberCoroutineScope()

    val context = LocalContext.current
    var pendingHighSpeed by remember { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingHighSpeed) {
            controller.startHighSpeed()
        }
        pendingHighSpeed = false
        if (!granted) {
            controller.notify("未授予本地网络权限，无法访问录音卡 HTTP 服务")
        }
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (hasWifiPermissions(context) || result.values.all { it }) {
            controller.startHighSpeed()
        } else {
            controller.notify("未获得附近设备/位置权限，可直接在系统 WLAN 中手动连接热点")
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        controller.connect(address)
    }

    fun enableBluetooth() {
        runCatching {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            controller.notify("无法打开蓝牙：${it.message}")
        }
    }

    fun requestHighSpeed() {
        if (s?.status?.recording == true) {
            controller.notify("录音中无法开启高速传输")
            return
        }
        if (!hasLocalNetworkPermission(context)) {
            pendingHighSpeed = true
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 && !isLocationServiceEnabled(context)) {
            controller.notify("定位服务未开启，自动连接可能失败，将转入手动连接")
        }
        if (!hasWifiPermissions(context)) {
            wifiPermissionLauncher.launch(wifiPermissionNames())
        } else {
            controller.startHighSpeed()
        }
    }

    val blePermission = rememberBlePermissionState(
        onGranted = { controller.selectDevice(address) },
    )

    LaunchedEffect(address, blePermission.granted) {
        if (blePermission.granted || address.startsWith("DEMO-")) {
            controller.selectDevice(address)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = device?.displayName ?: "录音卡",
            subtitle = address,
            onBack = onBack,
        ) {
            IconButton(onClick = {
                controller.refreshStatus()
                controller.requestAllFiles()
            }) {
                GlyphIcon(Glyph.REFRESH, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    GlyphIcon(Glyph.MORE, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("网络诊断") },
                        onClick = {
                            menuOpen = false
                            if (!diagnosticsRunning) {
                                diagnosticsRunning = true
                                diagnosticsScope.launch {
                                    val report = NetworkDiagnostics.run(
                                        context = context,
                                        host = ap.ip ?: "192.168.4.1",
                                    )
                                    diagnostics = report
                                    diagnosticsRunning = false
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("传输设置") },
                        onClick = {
                            menuOpen = false
                            onOpenTransferSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("文件管理") },
                        onClick = {
                            menuOpen = false
                            onOpenFiles()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("协议日志") },
                        onClick = {
                            menuOpen = false
                            onOpenLogs()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移除设备", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            confirmForget = true
                        },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!blePermission.granted && s?.demo != true) {
                item {
                    PermissionCard(
                        title = "需要蓝牙权限",
                        message = "连接录音卡需要「附近的设备」权限。",
                        actionLabel = "授权",
                        onAction = blePermission.request,
                    )
                }
            }
            if (needsLocalNetworkPermission() && !hasLocalNetworkPermission(context)) {
                item {
                    PermissionCard(
                        title = "需要本地网络权限",
                        message = "Android 17 默认禁止应用访问局域网设备；授权后才能访问录音卡的 " +
                            "HTTP 服务并下载文件。",
                        actionLabel = "授权本地网络",
                        onAction = {
                            pendingHighSpeed = false
                            localNetworkPermissionLauncher.launch(
                                Manifest.permission.ACCESS_LOCAL_NETWORK,
                            )
                        },
                    )
                }
            }
            item {
                ConnectionHero(
                    session = s,
                    device = device,
                    onReconnect = { controller.connect(address) },
                    onDisconnect = { controller.disconnect() },
                    onEnableBluetooth = { enableBluetooth() },
                )
            }

            if (s?.demo == true) {
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(
                                Glyph.INFO,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "演示模式：以下为本地模拟数据，不会连接或操作真实设备。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (s != null && s.isReady) {
                val status = s.status
                if (status != null) {
                    item { StatusOverview(session = s) }
                }
                item {
                    TransferSection(
                        session = s,
                        ap = ap,
                        onStartSync = { controller.startSync() },
                        onStopTransfer = { controller.stopTransfer() },
                        onStopHighSpeed = { controller.stopHighSpeed() },
                        onSwitchHighSpeed = { confirmSwitch = true },
                        onRequestHighSpeed = { requestHighSpeed() },
                        onResume = { controller.resumeTransfer() },
                        onDismissResume = { controller.dismissResume() },
                        onDismissAp = { controller.dismissApResult() },
                        onOpenWifiSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            }.onFailure {
                                controller.notify("无法打开 WLAN 设置：${it.message}")
                            }
                        },
                        onManualConfirm = { controller.confirmManualWifi() },
                    )
                }
                item {
                    QuickActions(
                        session = s,
                        ap = ap,
                        onSync = { controller.startSync() },
                        onHighSpeed = { requestHighSpeed() },
                        onFiles = onOpenFiles,
                        onSettings = onOpenTransferSettings,
                    )
                }
                if (device != null) {
                    item {
                        AutomationCard(
                            device = device,
                            onAutoSyncChange = { controller.setAutoSync(address, it) },
                            onPreferHighSpeedChange = { controller.setPreferHighSpeed(address, it) },
                            onMore = onOpenTransferSettings,
                        )
                    }
                }
                item {
                    PendingPreview(
                        session = s,
                        onOpenFiles = onOpenFiles,
                        onStartSync = { controller.startSync() },
                    )
                }
            } else if (s == null || s.phase == ConnPhase.FAILED || s.phase == ConnPhase.DISCONNECTED) {
                item { ConnectionHelpCard(session = s) }
            }

            item {
                DeviceInfoCard(session = s, device = device)
            }

            item {
                Text(
                    text = "协议 v4.0 · 自定义服务 0xA001 · 测试工具",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "移除 ${device?.displayName ?: "设备"}？",
            message = "将断开连接并忘记该设备，设备上的录音不会被删除。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                controller.forgetDevice(address)
                onBack()
            },
            onDismiss = { confirmForget = false },
        )
    }

    if (confirmSwitch) {
        ConfirmDialog(
            title = "切换到高速传输？",
            message = "当前蓝牙传输会先停止，然后开启设备热点，通过 WiFi 重新下载待上传文件。",
            confirmLabel = "切换",
            onConfirm = { controller.switchToHighSpeed() },
            onDismiss = { confirmSwitch = false },
        )
    }

    diagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnostics = null },
            title = { Text("网络诊断") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("recordcard-diag", report))
                    diagnostics = null
                }) {
                    Text("复制并关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = { diagnostics = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ConnectionHero(
    session: SessionState?,
    device: SavedDevice?,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val phase = session?.phase ?: ConnPhase.DISCONNECTED
    val ready = phase == ConnPhase.READY
    val name = session?.name ?: device?.displayName ?: "录音卡"
    val address = session?.address ?: device?.address.orEmpty()
    val gradient = if (ready) {
        listOf(Teal700, Color(0xFF073B33))
    } else {
        listOf(Color(0xFF37433F), Color(0xFF1B2421))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(gradient))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.CARD, modifier = Modifier.size(26.dp), tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace,
                )
            }
            val chipText = when (phase) {
                ConnPhase.READY -> "已连接"
                ConnPhase.CONNECTING -> "连接中"
                ConnPhase.DISCOVERING -> "发现服务"
                ConnPhase.FAILED -> "连接失败"
                ConnPhase.DISCONNECTED -> "未连接"
            }
            val chipColor = when (phase) {
                ConnPhase.READY -> Color(0xFF9BF2D8)
                ConnPhase.CONNECTING, ConnPhase.DISCOVERING -> Teal200
                ConnPhase.FAILED -> Color(0xFFFFB4AB)
                ConnPhase.DISCONNECTED -> Color.White.copy(alpha = 0.7f)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(chipColor),
                )
                Spacer(Modifier.width(5.dp))
                Text(chipText, style = MaterialTheme.typography.labelSmall, color = chipColor)
            }
        }

        Spacer(Modifier.height(16.dp))

        when (phase) {
            ConnPhase.READY -> {
                val status = session?.status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    HeroMetric("电量", status?.battery?.let { "${it.socPct}%" } ?: "--")
                    HeroMetric("MTU", (session?.mtu ?: 0).toString())
                    HeroMetric(
                        "信号",
                        session?.rssi?.let { "$it dBm" } ?: "--",
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDisconnect) {
                        Text("断开", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            ConnPhase.CONNECTING, ConnPhase.DISCOVERING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = session?.note ?: "正在连接…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDisconnect) {
                        Text("取消", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            else -> {
                Text(
                    text = session?.note ?: "设备未连接，点击下方按钮重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Teal700,
                    ),
                ) {
                    GlyphIcon(Glyph.BLUETOOTH, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重新连接")
                }
                if (session?.note?.contains("蓝牙未开启") == true) {
                    TextButton(onClick = onEnableBluetooth) {
                        Text("打开蓝牙", color = Color.White.copy(alpha = 0.9f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = device?.lastConnectedAt?.let { "上次连接：${relativeTime(it)}" }
                        ?: "尚未连接过此设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun StatusOverview(session: SessionState) {
    val status = session.status ?: return
    val battery = status.battery
    val storage = status.storage
    val recMode = RecMode.fromValue(status.recMode)
    val elapsedSeconds = liveRecordingSeconds(
        recording = status.recording,
        paused = status.recordingPaused,
        base = status.recordingSeconds,
    )
    val recValue = when {
        status.recordingPaused -> "已暂停"
        status.recording -> (elapsedSeconds * 1000L).formatDuration()
        else -> "待机"
    }
    val recDetail = when {
        status.recordingPaused -> "双击按键继续"
        status.recording -> recMode?.label ?: "录音中"
        else -> recMode?.label ?: "模式 ${status.recMode}"
    }
    val recordingChipText = when {
        status.recordingPaused -> "已暂停"
        status.recording -> "录音中"
        else -> "空闲"
    }
    val recordingChipColor = when {
        status.recordingPaused -> WarningAmber
        status.recording -> SuccessGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SectionCard(
        title = "设备状态",
        trailing = { StatusChip(recordingChipText, recordingChipColor) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                glyph = Glyph.BATTERY,
                label = "电量",
                value = battery?.let { "${it.socPct}%" } ?: "--",
                detail = battery?.let {
                    val charging = if (it.charging) " · 充电中" else ""
                    String.format("%.2fV · %d°C%s", it.voltageMv / 1000f, it.tempC, charging)
                },
                modifier = Modifier.weight(1f),
                accent = if ((battery?.socPct ?: 100) <= 20) MaterialTheme.colorScheme.error else SuccessGreen,
            )
            MetricTile(
                glyph = Glyph.STORAGE,
                label = "存储",
                value = storage?.usedBytes?.formatBytes() ?: "未挂载",
                detail = storage?.let { "剩余 ${it.freeBytes.formatBytes()}" } ?: "请检查 SD 卡",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                glyph = Glyph.SYNC,
                label = "待上传",
                value = "${status.pendingFiles} 个",
                detail = status.pendingBytes.formatBytes(),
                modifier = Modifier.weight(1f),
                accent = WarningAmber,
            )
            MetricTile(
                glyph = Glyph.MIC,
                label = "录音",
                value = recValue,
                detail = recDetail,
                modifier = Modifier.weight(1f),
                accent = if (status.recording) SuccessGreen else MaterialTheme.colorScheme.primary,
            )
        }
        if (storage != null && storage.totalBytes > 0) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已用 ${storage.usedBytes.formatBytes()} / ${storage.totalBytes.formatBytes()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            ProgressTrack(fraction = storage.usedFraction)
        }
    }
}

@Composable
private fun liveRecordingSeconds(
    recording: Boolean,
    paused: Boolean,
    base: Int,
): Int {
    var elapsed by remember(recording, paused, base) { mutableIntStateOf(base) }
    LaunchedEffect(recording, paused, base) {
        if (!recording || paused) return@LaunchedEffect
        while (true) {
            delay(1_000)
            elapsed += 1
        }
    }
    return elapsed
}

@Composable
private fun TransferSection(
    session: SessionState,
    ap: ApState,
    onStartSync: () -> Unit,
    onStopTransfer: () -> Unit,
    onStopHighSpeed: () -> Unit,
    onSwitchHighSpeed: () -> Unit,
    onRequestHighSpeed: () -> Unit,
    onResume: () -> Unit,
    onDismissResume: () -> Unit,
    onDismissAp: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onManualConfirm: () -> Unit,
) {
    val transfer = session.transfer
    val status = session.status
    val apActive = ap.phase != ApPhase.IDLE
    val modeChip = when {
        apActive -> "高速 WiFi"
        transfer?.mode == TransferMode.WIFI -> "高速 WiFi"
        transfer != null -> "蓝牙"
        else -> "空闲"
    }
    val chipColor = when {
        apActive || transfer != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SectionCard(title = "录音同步", trailing = { StatusChip(modeChip, chipColor) }) {
        when {
            apActive -> ApTransferContent(
                ap = ap,
                recording = status?.recording == true,
                storageMounted = status?.storage != null,
                onStop = onStopHighSpeed,
                onRetry = onRequestHighSpeed,
                onDismiss = onDismissAp,
                onOpenWifiSettings = onOpenWifiSettings,
                onManualConfirm = onManualConfirm,
            )

            transfer != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        text = if (transfer.mode == TransferMode.BLE) "BLE" else "WiFi",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = transfer.fileId.ifBlank { "准备中…" }.let { formatRecordingId(it) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (transfer.speedBps > 0) transfer.speedBps.formatSpeed() else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ProgressTrack(fraction = transfer.fraction)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${transfer.bytes.formatBytes()} / ${
                            transfer.total.takeIf { it > 0 }?.formatBytes() ?: "--"
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (transfer.fileCount > 0) {
                        Text(
                            text = "${transfer.fileIndex.coerceAtLeast(1)}/${transfer.fileCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onStopTransfer,
                        modifier = Modifier.weight(1f),
                    ) {
                        GlyphIcon(Glyph.STOP, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                    FilledTonalButton(
                        onClick = onSwitchHighSpeed,
                        enabled = transfer.mode == TransferMode.BLE && status?.recording != true,
                        modifier = Modifier.weight(1f),
                    ) {
                        GlyphIcon(Glyph.WIFI, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("切换高速")
                    }
                }
                if (status?.recording == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "录音中无法开启高速传输，请先结束录音。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningAmber,
                    )
                }
            }

            session.pendingResume != null -> {
                val resume = session.pendingResume!!
                Text(
                    text = "上次传输未完成",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatRecordingId(resume.fileId)} · 已接收 ${resume.bytes.formatBytes()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Text("断点续传")
                    }
                    OutlinedButton(onClick = onDismissResume, modifier = Modifier.weight(1f)) {
                        Text("忽略")
                    }
                }
            }

            (status?.pendingFiles ?: 0) > 0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "有 ${status?.pendingFiles ?: 0} 个文件待上传",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = status?.pendingBytes?.formatBytes() ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onStartSync,
                        enabled = !session.demo,
                    ) {
                        GlyphIcon(Glyph.SYNC, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始同步")
                    }
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(
                        Glyph.CHECK,
                        modifier = Modifier.size(18.dp),
                        tint = SuccessGreen,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "设备上没有待上传文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApTransferContent(
    ap: ApState,
    recording: Boolean,
    storageMounted: Boolean,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onManualConfirm: () -> Unit,
) {
    if (!storageMounted) {
        Text(
            text = "设备存储未挂载（SD 卡），HTTP 文件服务可能不可用。请检查录音卡存储后重新上电。",
            style = MaterialTheme.typography.labelSmall,
            color = WarningAmber,
        )
        Spacer(Modifier.height(8.dp))
    }
    when (ap.phase) {
        ApPhase.ENABLING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在开启设备热点…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        ApPhase.WAITING_WIFI, ApPhase.LISTING -> {
            Text(
                text = if (ap.phase == ApPhase.LISTING) "正在读取文件列表…" else "正在连接设备热点",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = ap.ssid?.let { "热点：$it" } ?: "热点：设备热点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "系统会弹出 WiFi 连接确认，请点击允许；下载期间请保持本页面在前台。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onStop) { Text("取消") }
        }

        ApPhase.WAITING_MANUAL -> {
            Text("请手动连接设备热点", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "热点：${ap.ssid ?: "RecordingCard-XXXX"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "系统没有自动弹出连接确认（部分机型会被通知/后台弹窗权限拦截）。" +
                    "请到系统 WLAN 设置中连接上面的热点（无密码），返回后点「我已连接」。" +
                    "若系统提示「无法访问互联网」，请选择保持连接/继续使用该网络。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ap.message.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = ap.message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenWifiSettings, modifier = Modifier.weight(1f)) {
                    Text("打开 WLAN 设置")
                }
                OutlinedButton(onClick = onManualConfirm, modifier = Modifier.weight(1f)) {
                    Text("我已连接")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onStop) { Text("取消") }
            Text(
                text = "提示：设备热点约 90 秒无下载会自动关闭；请临时关闭 WLAN+ / 智能选网，" +
                    "避免系统自动切回原 WiFi。\n小米/红米若始终不弹连接框：应用信息 → 权限管理 → " +
                    "其他权限 → 允许「后台弹出界面」后重试。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ApPhase.DOWNLOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge("高速 WiFi", MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = ap.fileId?.let { formatRecordingId(it) } ?: "下载中…",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ap.speedBps.formatSpeed(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressTrack(fraction = ap.fraction)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = "${ap.bytesDone.formatBytes()} / ${ap.bytesTotal.formatBytes()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${ap.fileIndex}/${ap.fileCount} · 已完成 ${ap.completed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                GlyphIcon(Glyph.STOP, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("停止高速传输")
            }
        }

        ApPhase.DONE -> {
            Text(ap.message ?: "高速传输完成", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }

        ApPhase.ERROR -> {
            Text(
                text = ap.message ?: "高速传输失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (recording) {
                Text(
                    text = "录音中无法开启热点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningAmber,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
            }
        }

        ApPhase.IDLE -> Unit
    }
}

@Composable
private fun QuickActions(
    session: SessionState,
    ap: ApState,
    onSync: () -> Unit,
    onHighSpeed: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    val ready = session.isReady && !session.demo
    val status = session.status
    val busy = session.transfer != null || ap.phase != ApPhase.IDLE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                glyph = Glyph.SYNC,
                label = "同步录音",
                enabled = ready && !busy && (status?.pendingFiles ?: 0) > 0,
                modifier = Modifier.weight(1f),
                onClick = onSync,
            )
            ActionTile(
                glyph = Glyph.WIFI,
                label = "高速传输",
                enabled = ready && !busy && status?.recording != true,
                modifier = Modifier.weight(1f),
                onClick = onHighSpeed,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(
                glyph = Glyph.FOLDER,
                label = "文件管理",
                enabled = session.isReady,
                modifier = Modifier.weight(1f),
                onClick = onFiles,
            )
            ActionTile(
                glyph = Glyph.SETTINGS,
                label = "传输设置",
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun AutomationCard(
    device: SavedDevice,
    onAutoSyncChange: (Boolean) -> Unit,
    onPreferHighSpeedChange: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    SectionCard(title = "自动化") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("有待上传文件时自动传输", style = MaterialTheme.typography.titleSmall)
                Text(
                    "连接设备或录音结束后自动开始同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = device.autoSync, onCheckedChange = onAutoSyncChange)
        }
        ThinDivider(modifier = Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("优先使用高速传输", style = MaterialTheme.typography.titleSmall)
                Text(
                    "手动同步时直接开启设备热点，通过 WiFi 下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = device.preferHighSpeed, onCheckedChange = onPreferHighSpeedChange)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onMore) { Text("更多传输设置") }
    }
}

@Composable
private fun PendingPreview(
    session: SessionState,
    onOpenFiles: () -> Unit,
    onStartSync: () -> Unit,
) {
    val pending = session.pendingFiles
    val status = session.status
    SectionCard(
        title = "待上传文件",
        trailing = {
            TextButton(onClick = onOpenFiles) { Text("全部") }
        },
    ) {
        if (pending.isEmpty()) {
            Text(
                text = if ((status?.pendingFiles ?: 0) > 0) {
                    "共 ${status?.pendingFiles} 个文件，打开文件管理查看"
                } else {
                    "暂无待上传文件"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            pending.take(3).forEach { file ->
                RemoteFileRow(file)
            }
            if (pending.size > 3) {
                Text(
                    text = "还有 ${pending.size - 3} 个文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if ((status?.pendingFiles ?: 0) > 0 && session.transfer == null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onStartSync,
                    enabled = !session.demo,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GlyphIcon(Glyph.DOWNLOAD, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("同步到手机")
                }
            }
        }
    }
}

@Composable
private fun RemoteFileRow(file: RemoteFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(
            Glyph.WAVE,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatRecordingId(file.id),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = file.size.formatBytes(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionHelpCard(session: SessionState?) {
    SectionCard(title = "连接不上？") {
        val note = session?.note
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        HelpStep("1", "确认录音卡已开机，屏幕显示蓝牙广播状态")
        HelpStep("2", "如果其他手机已连接该设备，请先断开")
        HelpStep("3", "确认手机蓝牙已开启，且已授予附近设备权限")
        HelpStep("4", "靠近设备至 1 米内，点击「重新连接」")
    }
}

@Composable
private fun HelpStep(index: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceInfoCard(session: SessionState?, device: SavedDevice?) {
    val identity = session?.identity
    val status = session?.status
    val deviceId = identity?.deviceId ?: device?.deviceId
    val serial = identity?.serial ?: device?.serial
    val firmware = identity?.firmware ?: device?.firmware
    val hardware = identity?.hardware ?: device?.hardware
    val wifiMac = identity?.wifiMac ?: device?.wifiMac
    val model = identity?.model

    SectionCard(title = "设备信息") {
        InfoRow("设备 ID", deviceId ?: "—", mono = true)
        InfoRow("序列号", serial ?: "—", mono = true)
        InfoRow("应用版本", firmware ?: "—")
        InfoRow("硬件版本", hardware ?: "—")
        InfoRow("型号", model ?: "RecordingCard")
        InfoRow("WiFi MAC", wifiMac ?: "—", mono = true)
        InfoRow(
            "录音模式",
            RecMode.fromValue(status?.recMode ?: -1)?.label ?: "—",
        )
        InfoRow("协议版本", "v4.0 · 0xA001")
    }
}
