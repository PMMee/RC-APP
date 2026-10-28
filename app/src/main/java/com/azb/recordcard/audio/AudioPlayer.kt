package com.azb.recordcard.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.azb.recordcard.core.AppLog
import com.azb.recordcard.data.LocalRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val recordingId: String? = null,
    val title: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val error: String? = null,
)

/** Simple single-track player for the local recordings library. */
class AudioPlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun play(recording: LocalRecording) {
        if (_state.value.recordingId == recording.id && player != null) {
            toggle()
            return
        }
        releaseInternal()
        _state.value = PlayerState(
            recordingId = recording.id,
            title = recording.id,
            buffering = true,
            durationMs = recording.durationMs,
        )
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(recording.file.absolutePath)
            mp.setOnPreparedListener { prepared ->
                player = prepared
                prepared.start()
                _state.value = _state.value.copy(
                    playing = true,
                    buffering = false,
                    durationMs = prepared.duration.toLong().coerceAtLeast(0),
                )
                startTicker()
            }
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(
                    playing = false,
                    positionMs = it.duration.toLong().coerceAtLeast(0),
                )
                stopTicker()
            }
            mp.setOnErrorListener { _, what, extra ->
                AppLog.e("Player", "播放失败 what=$what extra=$extra")
                _state.value = _state.value.copy(
                    playing = false,
                    buffering = false,
                    error = "无法播放该录音",
                )
                stopTicker()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            AppLog.e("Player", "打开录音失败: ${e.message}")
            _state.value = _state.value.copy(buffering = false, error = "无法打开录音")
            releaseInternal()
        }
    }

    fun toggle() {
        val mp = player ?: return
        val state = _state.value
        if (state.buffering) return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                _state.value = state.copy(playing = false)
                stopTicker()
            } else {
                if (state.durationMs > 0 && state.positionMs >= state.durationMs - 200) {
                    runCatching { mp.seekTo(0) }
                    _state.value = state.copy(positionMs = 0)
                }
                mp.start()
                _state.value = _state.value.copy(playing = true)
                startTicker()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player?.let {
            runCatching { it.seekTo(positionMs.toInt()) }
            _state.value = _state.value.copy(positionMs = positionMs)
        }
    }

    fun stop() {
        releaseInternal()
        _state.value = PlayerState()
    }

    fun release() {
        releaseInternal()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val position = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                _state.value = _state.value.copy(positionMs = position)
                delay(300)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releaseInternal() {
        stopTicker()
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}
