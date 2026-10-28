package com.azb.recordcard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.app.ConnPhase
import com.azb.recordcard.app.SessionState
import com.azb.recordcard.data.SavedDevice
import com.azb.recordcard.ui.components.Badge
import com.azb.recordcard.ui.components.ConfirmDialog
import com.azb.recordcard.ui.components.DeviceAvatar
import com.azb.recordcard.ui.components.EmptyState
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.PageHeader
import com.azb.recordcard.ui.components.PermissionCard
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.components.StatusChip
import com.azb.recordcard.ui.relativeTime
import com.azb.recordcard.ui.rememberBlePermissionState
import com.azb.recordcard.ui.theme.SuccessGreen
import com.azb.recordcard.ui.theme.WarningAmber

@Composable
fun DevicesScreen(
    controller: AppController,
    onAddDevice: () -> Unit,
    onOpenDevice: (String) -> Unit,
) {
    val devices by controller.devices.collectAsState()
    val session by controller.session.collectAsState()
    val permission = rememberBlePermissionState()
    var forgetTarget by remember { mutableStateOf<SavedDevice?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "我的录音卡",
                subtitle = if (devices.isEmpty()) {
                    "还没有添加设备"
                } else {
                    "已添加 ${devices.size} 台 · 点击卡片进入"
                },
            ) {
                IconButton(onClick = onAddDevice) {
                    GlyphIcon(Glyph.PLUS, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!permission.granted) {
            item {
                PermissionCard(
                    title = "需要蓝牙权限",
                    message = "扫描和连接录音卡需要「附近的设备」权限，仅用于发现设备。",
                    actionLabel = "去授权",
                    onAction = permission.request,
                )
            }
        }

        if (devices.isEmpty()) {
            item {
                EmptyState(
                    glyph = Glyph.BLUETOOTH,
                    title = "还没有录音卡",
                    description = "让录音卡保持开机并靠近手机，然后扫描添加。",
                    actionLabel = "添加录音卡",
                    onAction = onAddDevice,
                )
            }
        } else {
            items(devices, key = { it.address }) { device ->
                DeviceCard(
                    device = device,
                    session = session,
                    onClick = { onOpenDevice(device.address) },
                    onForget = { forgetTarget = device },
                )
            }
            item {
                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = onAddDevice,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GlyphIcon(Glyph.PLUS, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加录音卡")
                }
            }
            item {
                Text(
                    text = "提示：连接后可同步录音、切换高速 WiFi 传输并管理设备文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }

    forgetTarget?.let { target ->
        ConfirmDialog(
            title = "移除 ${target.displayName}？",
            message = "移除后将忘记此设备，需要重新扫描添加。设备上的录音不会被删除。",
            confirmLabel = "移除",
            destructive = true,
            onConfirm = { controller.forgetDevice(target.address) },
            onDismiss = { forgetTarget = null },
        )
    }
}

@Composable
private fun DeviceCard(
    device: SavedDevice,
    session: SessionState?,
    onClick: () -> Unit,
    onForget: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = session?.address == device.address
    val phase = if (active) session?.phase else null

    SectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar()
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        active && phase == ConnPhase.READY -> StatusChip("已连接", SuccessGreen)
                        active && (phase == ConnPhase.CONNECTING || phase == ConnPhase.DISCOVERING) ->
                            StatusChip("正在连接", WarningAmber)
                        active && phase == ConnPhase.FAILED ->
                            StatusChip("连接失败", MaterialTheme.colorScheme.error)
                        active -> StatusChip("未连接", MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(
                            text = device.lastConnectedAt?.let { "上次连接 ${relativeTime(it)}" }
                                ?: "未连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    device.lastBatteryPct?.let { pct ->
                        Badge("$pct%", MaterialTheme.colorScheme.primary)
                    }
                    if ((device.lastPendingFiles ?: 0) > 0) {
                        Badge("待传 ${device.lastPendingFiles}", WarningAmber)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    GlyphIcon(
                        Glyph.MORE,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("移除设备") },
                        onClick = {
                            menuOpen = false
                            onForget()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(if (active && phase == ConnPhase.READY) "进入设备" else "连接并进入")
            Spacer(Modifier.weight(1f))
            GlyphIcon(Glyph.CHEVRON_RIGHT, modifier = Modifier.size(16.dp))
        }
    }
}
