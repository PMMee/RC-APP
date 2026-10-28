package com.azb.recordcard.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun Long.formatBytes(): String {
    if (this <= 0) return "0 B"
    val kb = this / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        else -> "$this B"
    }
}

fun Long.formatSpeed(): String = "${formatBytes()}/s"

fun Long.formatDuration(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

fun formatFullDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

fun relativeTime(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "从未连接"
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        days < 7 -> "${days} 天前"
        else -> formatDateTime(timestamp)
    }
}

/** Turns a device recording id (yyyyMMdd_HHmmss[_pN]) into a readable date. */
fun formatRecordingId(id: String): String {
    val cleaned = id.removePrefix("pending_").removePrefix("uploaded_")
    val stem = cleaned.take(15)
    if (stem.length == 15 && stem[8] == '_') {
        val parsed = runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stem)
        }.getOrNull()
        if (parsed != null) {
            val suffix = cleaned.drop(15).replace('_', ' ').trim()
            val base = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed)
            return if (suffix.isBlank()) base else "$base · $suffix"
        }
    }
    return cleaned
}

fun formatIdShort(id: String): String = formatRecordingId(id)
