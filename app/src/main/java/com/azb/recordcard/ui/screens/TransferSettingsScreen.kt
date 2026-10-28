package com.azb.recordcard.ui.screens

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.app.ConnPhase
import com.azb.recordcard.ui.components.BackHeader
import com.azb.recordcard.ui.components.DeviceAvatar
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.InfoRow
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.components.StatusChip
import com.azb.recordcard.ui.components.ThinDivider
import com.azb.recordcard.ui.theme.SuccessGreen

@Composable
fun TransferSettingsScreen(
    controller: AppController,
    address: String,
    onBack: () -> Unit,
) {
    val devices by controller.devices.collectAsState()
    val session by controller.session.collectAsState()
    val device = devices.firstOrNull { it.address == address }
    val s = session?.takeIf { it.address == address }

    LaunchedEffect(address) { controller.selectDevice(address) }

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = "传输设置",
            subtitle = device?.displayName ?: address,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(size = 44)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device?.displayName ?: "录音卡",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = address,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s?.isReady == true) {
                            StatusChip("已连接", SuccessGreen)
                        } else {
                            StatusChip(
                                text = "未连接",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "自动传输") {
                    SettingSwitch(
                        title = "有待上传文件时自动开始传输",
                        description = "连接设备后、或录音结束产生新文件后，自动发起蓝牙同步。",
                        checked = device?.autoSync ?: true,
                        onCheckedChange = { controller.setAutoSync(address, it) },
                    )
                    ThinDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SettingSwitch(
                        title = "优先使用高速传输",
                        description = "手动点击「同步录音」时直接开启设备热点，通过 WiFi 下载，速率远高于蓝牙。",
                        checked = device?.preferHighSpeed ?: false,
                        onCheckedChange = { controller.setPreferHighSpeed(address, it) },
                    )
                }
            }

            item {
                SectionCard(title = "传输方式") {
                    Text(
                        text = "蓝牙传输",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "无需切换网络，连接后即可同步。设备按 12 帧一个窗口流控，单帧最大 486B，"
                            + "速率通常 20–60 KB/s。传输过程中可随时切换高速传输。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "高速传输（WiFi）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "App 让设备开启热点（RecordingCard-XXXX，无密码），手机连接后通过 "
                            + "HTTP 下载文件，速率通常 1–3 MB/s。下载完成会自动标记设备文件为已上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(
                            Glyph.WIFI,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "首次连接会弹出系统 WiFi 确认框，请选择允许。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "协议参数") {
                    InfoRow("传输窗口", "12 帧 / 信用")
                    InfoRow("单帧载荷", "min(486, MTU − 12)")
                    InfoRow("状态心跳", "每 10 秒")
                    InfoRow("断点保留", "5 分钟（设备内存）")
                    InfoRow("AP 空闲超时", "90 秒")
                    InfoRow("通知上限", "MTU − 3")
                }
            }

            item {
                SectionCard(title = "本地数据") {
                    Text(
                        text = "未完成的传输分片保存在本地缓存中，可手动清理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { controller.clearPartials() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        GlyphIcon(Glyph.DELETE, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清除未完成分片")
                    }
                }
            }

            item {
                if (s?.phase == ConnPhase.CONNECTING || s?.phase == ConnPhase.DISCOVERING) {
                    Text(
                        text = "设备正在连接，设置会在连接后生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
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
