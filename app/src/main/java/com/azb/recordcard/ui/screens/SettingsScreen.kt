package com.azb.recordcard.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.azb.recordcard.BuildConfig
import com.azb.recordcard.app.AppController
import com.azb.recordcard.ui.components.ConfirmDialog
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.InfoRow
import com.azb.recordcard.ui.components.PageHeader
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.components.ThinDivider
import com.azb.recordcard.ui.hasNotificationPermission

@Composable
fun SettingsScreen(
    controller: AppController,
    onOpenLogs: () -> Unit,
) {
    val settings by controller.settings.collectAsState()
    val devices by controller.devices.collectAsState()
    val context = LocalContext.current
    var prefix by remember(settings.scanPrefix) { mutableStateOf(settings.scanPrefix) }
    var confirmClearLogs by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "设置",
                subtitle = "扫描、传输与调试选项",
            )
        }

        item {
            SectionCard(title = "扫描") {
                Text("设备名前缀", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "只显示名称以此开头的蓝牙设备；默认匹配 RecordingCard。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("RecordingCard") },
                    trailingIcon = {
                        TextButton(onClick = {
                            val value = prefix.trim().ifBlank { "RecordingCard" }
                            prefix = value
                            controller.updateSettings { it.copy(scanPrefix = value) }
                            controller.notify("扫描前缀已保存")
                        }) {
                            Text("保存")
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已添加 ${devices.size} 台设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "新设备默认值") {
                SettingSwitchRow(
                    title = "自动同步",
                    description = "新添加的设备默认在有待上传文件时自动传输。",
                    checked = settings.autoSyncDefault,
                    onCheckedChange = {
                        controller.updateSettings { current -> current.copy(autoSyncDefault = it) }
                    },
                )
                ThinDivider(modifier = Modifier.padding(vertical = 12.dp))
                SettingSwitchRow(
                    title = "优先高速传输",
                    description = "新添加的设备默认使用 WiFi 高速传输。",
                    checked = settings.preferHighSpeedDefault,
                    onCheckedChange = {
                        controller.updateSettings { current -> current.copy(preferHighSpeedDefault = it) }
                    },
                )
                ThinDivider(modifier = Modifier.padding(vertical = 12.dp))
                SettingSwitchRow(
                    title = "传输时保持屏幕常亮",
                    description = "传输进行中阻止屏幕熄灭，避免传输被系统挂起。",
                    checked = settings.keepScreenOn,
                    onCheckedChange = {
                        controller.updateSettings { current -> current.copy(keepScreenOn = it) }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "以上仅影响新添加的设备，已有设备可在各自的「传输设置」中修改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "调试") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("协议日志", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "记录 BLE 命令、状态推送与传输帧，可复制排查。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenLogs) { Text("查看") }
                }
                ThinDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("通知权限", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hasNotificationPermission(context)) {
                                "已开启，传输时显示进度通知"
                            } else {
                                "未开启，传输进度通知不会显示"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) {
                        Text("设置")
                    }
                }
                ThinDivider(modifier = Modifier.padding(vertical = 10.dp))
                OutlinedButton(
                    onClick = { confirmClearLogs = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清空协议日志")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { controller.clearPartials() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除未完成分片")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { controller.addDemoDevice() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GlyphIcon(Glyph.CARD, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加演示设备（界面预览）")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "演示设备使用本地模拟数据，不会连接真实硬件，用于预览设备页布局。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "关于") {
                InfoRow("应用版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("协议版本", "BLE GATT v4.0")
                InfoRow("自定义服务", "0xA001")
                InfoRow("设备名称", "RecordingCard-XXXX")
                InfoRow("设备名来源", "BT MAC 后 2 字节")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "录音卡协议测试工具。支持扫描连接、状态监控、蓝牙同步、WiFi 高速传输、"
                        + "断点续传、文件管理与本地播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(
                        Glyph.INFO,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "本工具仅供设备调试使用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmClearLogs) {
        ConfirmDialog(
            title = "清空协议日志？",
            message = "仅清除本机内存中的调试日志，不影响设备。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = { controller.clearLogs() },
            onDismiss = { confirmClearLogs = false },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
