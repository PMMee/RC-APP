package com.azb.recordcard.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.azb.recordcard.app.AppController
import com.azb.recordcard.audio.PlayerState
import com.azb.recordcard.data.LocalRecording
import com.azb.recordcard.ui.components.ConfirmDialog
import com.azb.recordcard.ui.components.EmptyState
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.PageHeader
import com.azb.recordcard.ui.components.SectionCard
import com.azb.recordcard.ui.formatBytes
import com.azb.recordcard.ui.formatDuration
import com.azb.recordcard.ui.formatRecordingId
import com.azb.recordcard.ui.theme.SuccessGreen

@Composable
fun RecordingsScreen(
    controller: AppController,
    onOpenDevice: (String) -> Unit,
) {
    val recordings by controller.recordings.collectAsState()
    val player by controller.playerState.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<LocalRecording?>(null) }
    var openMenuFor by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = "我的录音",
            subtitle = if (recordings.isEmpty()) {
                "同步后的录音会保存在这里"
            } else {
                "${recordings.size} 个录音 · 共 ${recordings.sumOf { it.size }.formatBytes()}"
            },
        ) {
            IconButton(onClick = { controller.refreshLibrary() }) {
                GlyphIcon(Glyph.REFRESH, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (recordings.isEmpty()) {
            EmptyState(
                glyph = Glyph.WAVE,
                title = "还没有录音",
                description = "在「我的设备」中连接录音卡并同步，录音会自动保存到这里。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recordings, key = { it.file.absolutePath }) { recording ->
                    RecordingRow(
                        recording = recording,
                        playing = player.recordingId == recording.id && player.playing,
                        isCurrent = player.recordingId == recording.id,
                        menuOpen = openMenuFor == recording.id,
                        onPlay = { controller.playRecording(recording) },
                        onMenuOpen = { openMenuFor = recording.id },
                        onMenuDismiss = { openMenuFor = null },
                        onOpenDevice = recording.deviceAddress?.let { address ->
                            {
                                openMenuFor = null
                                onOpenDevice(address)
                            }
                        },
                        onShare = {
                            openMenuFor = null
                            shareRecording(context, recording)
                        },
                        onDelete = {
                            openMenuFor = null
                            pendingDelete = recording
                        },
                    )
                }
            }
        }

        if (player.recordingId != null) {
            MiniPlayer(
                player = player,
                onToggle = { controller.togglePlayback() },
                onSeek = { controller.seekTo(it) },
                onClose = { controller.stopPlayback() },
            )
        }
    }

    pendingDelete?.let { recording ->
        ConfirmDialog(
            title = "删除录音？",
            message = "${formatRecordingId(recording.id)} 将从手机存储中删除，设备上的文件不受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { controller.deleteRecording(recording) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun RecordingRow(
    recording: LocalRecording,
    playing: Boolean,
    isCurrent: Boolean,
    menuOpen: Boolean,
    onPlay: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onOpenDevice: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onPlay)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    )
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph = if (playing) Glyph.PAUSE else Glyph.PLAY,
                    modifier = Modifier.size(18.dp),
                    tint = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatRecordingId(recording.id),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (recording.durationMs > 0) {
                            recording.durationMs.formatDuration()
                        } else {
                            "--:--"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = recording.size.formatBytes(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = recording.formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!recording.deviceName.isNullOrBlank()) {
                    Text(
                        text = "来自 ${recording.deviceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen),
                )
                Spacer(Modifier.width(8.dp))
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
                    if (onOpenDevice != null) {
                        DropdownMenuItem(
                            text = { Text("查看来源设备") },
                            onClick = onOpenDevice,
                        )
                    }
                    DropdownMenuItem(text = { Text("分享") }, onClick = onShare)
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    player: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val duration = player.durationMs.coerceAtLeast(1L)
    var dragValue by remember(player.recordingId) { mutableFloatStateOf(-1f) }
    val position = if (dragValue >= 0f) dragValue.toLong() else player.positionMs

    SectionCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    if (player.playing) Glyph.PAUSE else Glyph.PLAY,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = player.title?.let { formatRecordingId(it) } ?: "正在播放",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        if (dragValue >= 0f) {
                            onSeek(dragValue.toLong())
                            dragValue = -1f
                        }
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ),
                )
                Row {
                    Text(
                        text = position.formatDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (player.buffering) "加载中…" else duration.formatDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose) {
                GlyphIcon(
                    Glyph.CLOSE,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun shareRecording(context: Context, recording: LocalRecording) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            recording.file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (recording.extension == "ogg") "audio/ogg" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, recording.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享录音"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
