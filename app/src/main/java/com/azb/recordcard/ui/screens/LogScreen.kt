package com.azb.recordcard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.core.LogEntry
import com.azb.recordcard.core.LogLevel
import com.azb.recordcard.ui.components.BackHeader
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.components.ThinDivider
import com.azb.recordcard.ui.formatClock
import com.azb.recordcard.ui.theme.DangerRed
import com.azb.recordcard.ui.theme.Teal700
import com.azb.recordcard.ui.theme.WarningAmber

private enum class LogFilter(val label: String) {
    ALL("全部"),
    INFO("信息"),
    WARN("警告"),
    ERROR("错误"),
}

@Composable
fun LogScreen(
    controller: AppController,
    onBack: () -> Unit,
) {
    val logs by controller.logs.collectAsState()
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val listState = rememberLazyListState()

    val filtered = remember(logs, filter) {
        when (filter) {
            LogFilter.ALL -> logs
            LogFilter.INFO -> logs.filter { it.level == LogLevel.INFO }
            LogFilter.WARN -> logs.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
            LogFilter.ERROR -> logs.filter { it.level == LogLevel.ERROR }
        }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = "协议日志",
            subtitle = "${logs.size} 条 · 仅保存在内存",
            onBack = onBack,
        ) {
            IconButton(onClick = {
                val text = logs.joinToString("\n") {
                    "${formatClock(it.time)} [${it.level}] ${it.tag}: ${it.message}"
                }
                if (text.isBlank()) {
                    Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("recordcard-log", text))
                    Toast.makeText(context, "已复制 ${logs.size} 条日志", Toast.LENGTH_SHORT).show()
                }
            }) {
                GlyphIcon(Glyph.COPY, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { controller.clearLogs() }) {
                GlyphIcon(Glyph.DELETE, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { entry ->
                FilterChip(
                    selected = filter == entry,
                    onClick = { filter = entry },
                    label = { Text(entry.label) },
                )
            }
        }

        ThinDivider(modifier = Modifier.padding(horizontal = 16.dp))

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.padding(top = 40.dp))
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "连接设备后，命令、状态与传输帧会显示在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(filtered) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> Teal700
        LogLevel.WARN -> WarningAmber
        LogLevel.ERROR -> DangerRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatClock(entry.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.level.name.take(4),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}
