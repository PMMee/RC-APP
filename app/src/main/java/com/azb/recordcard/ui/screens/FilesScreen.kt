package com.azb.recordcard.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.protocol.RemoteFile
import com.azb.recordcard.ui.components.BackHeader
import com.azb.recordcard.ui.components.Badge
import com.azb.recordcard.ui.components.ConfirmDialog
import com.azb.recordcard.ui.components.EmptyState
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.ProgressTrack
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.formatBytes
import com.azb.recordcard.ui.formatRecordingId
import com.azb.recordcard.ui.theme.SuccessGreen
import com.azb.recordcard.ui.theme.WarningAmber

private enum class FileFilter(val label: String) {
    ALL("全部"),
    PENDING("待上传"),
    UPLOADED("已上传"),
}

@Composable
fun FilesScreen(
    controller: AppController,
    address: String,
    onBack: () -> Unit,
) {
    val session by controller.session.collectAsState()
    val s = session?.takeIf { it.address == address }
    var filter by rememberSaveable { mutableStateOf(FileFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFormat by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var openMenuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(address) { controller.selectDevice(address) }
    LaunchedEffect(s?.isReady) {
        if (s?.isReady == true) controller.refreshFiles()
    }

    val allFiles = s?.allFiles.orEmpty()
    val filtered = remember(allFiles, filter) {
        when (filter) {
            FileFilter.ALL -> allFiles.sortedByDescending { it.id }
            FileFilter.PENDING -> allFiles.filter { !it.uploaded }.sortedByDescending { it.id }
            FileFilter.UPLOADED -> allFiles.filter { it.uploaded }.sortedByDescending { it.id }
        }
    }
    val storage = s?.status?.storage

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = "文件管理",
            subtitle = s?.name ?: address,
            onBack = onBack,
        ) {
            IconButton(onClick = { controller.refreshFiles() }) {
                GlyphIcon(Glyph.REFRESH, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    GlyphIcon(Glyph.MORE, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新列表") },
                        onClick = {
                            menuOpen = false
                            controller.refreshFiles()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("格式化存储", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            confirmFormat = true
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
            item {
                SectionCard(title = "存储空间") {
                    if (storage == null) {
                        Text(
                            text = "SD 卡未挂载或设备未连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "已用 ${storage.usedBytes.formatBytes()} / ${storage.totalBytes.formatBytes()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "剩余 ${storage.freeBytes.formatBytes()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ProgressTrack(
                            fraction = storage.usedFraction,
                            color = if (storage.usedFraction > 0.9f) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileFilter.entries.forEach { entry ->
                        val count = when (entry) {
                            FileFilter.ALL -> allFiles.size
                            FileFilter.PENDING -> allFiles.count { !it.uploaded }
                            FileFilter.UPLOADED -> allFiles.count { it.uploaded }
                        }
                        FilterChip(
                            selected = filter == entry,
                            onClick = { filter = entry },
                            label = { Text("${entry.label} $count") },
                        )
                    }
                }
            }

            if (s?.isReady != true) {
                item {
                    SectionCard {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "设备未连接",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "连接后才能读取设备文件列表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { controller.connect(address) }) {
                                Text("重新连接")
                            }
                        }
                    }
                }
            } else if (s?.allFilesLoaded != true) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "正在读取文件列表…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        glyph = Glyph.FOLDER,
                        title = if (filter == FileFilter.PENDING) "没有待上传文件" else "没有文件",
                        description = if (filter == FileFilter.PENDING) {
                            "所有录音都已上传到手机。"
                        } else {
                            "录音卡上还没有录音文件。"
                        },
                        actionLabel = "刷新",
                        onAction = { controller.refreshFiles() },
                    )
                }
            } else {
                items(filtered, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        menuOpen = openMenuFor == file.id,
                        onMenuOpen = { openMenuFor = file.id },
                        onMenuDismiss = { openMenuFor = null },
                        onMarkUploaded = {
                            openMenuFor = null
                            controller.markUploaded(file.id)
                        },
                        onMarkPending = {
                            openMenuFor = null
                            controller.markPending(file.id)
                        },
                        onDelete = {
                            openMenuFor = null
                            pendingDelete = file
                        },
                    )
                }
            }

            item {
                Text(
                    text = "说明：点击右上角可格式化存储；删除操作只影响设备上的文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (confirmFormat) {
        ConfirmDialog(
            title = "格式化存储？",
            message = "会清空录音卡 SD 卡上的全部录音，且无法恢复。录音中或 WiFi 传输时设备会拒绝此操作。",
            confirmLabel = "格式化",
            destructive = true,
            onConfirm = { controller.formatDevice() },
            onDismiss = { confirmFormat = false },
        )
    }

    pendingDelete?.let { file ->
        ConfirmDialog(
            title = "删除录音？",
            message = "${formatRecordingId(file.id)}（${file.size.formatBytes()}）将从设备上永久删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { controller.deleteRemoteFile(file.id) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onMarkUploaded: () -> Unit,
    onMarkPending: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    Glyph.WAVE,
                    modifier = Modifier.size(22.dp),
                    tint = if (file.uploaded) SuccessGreen else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatRecordingId(file.id),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = file.size.formatBytes(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = file.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (file.uploaded) {
                Badge("已上传", SuccessGreen)
            } else {
                Badge("待上传", WarningAmber)
            }
            Box {
                IconButton(onClick = onMenuOpen) {
                    GlyphIcon(
                        Glyph.MORE,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                    if (file.uploaded) {
                        DropdownMenuItem(
                            text = { Text("标记为待上传") },
                            onClick = onMarkPending,
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("标记为已上传") },
                            onClick = onMarkUploaded,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}
