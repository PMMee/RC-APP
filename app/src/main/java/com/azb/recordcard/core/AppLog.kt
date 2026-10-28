package com.azb.recordcard.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * In-memory protocol console. Every BLE command, notification and transfer
 * state transition is recorded so the app can be used as a test tool.
 */
object AppLog {
    private const val MAX_ENTRIES = 800
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = add(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = add(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = add(LogLevel.ERROR, tag, message)

    private fun add(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        _entries.update { current ->
            if (current.size >= MAX_ENTRIES) {
                current.subList(current.size - MAX_ENTRIES + 1, current.size).toList() + entry
            } else {
                current + entry
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
