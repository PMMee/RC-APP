package com.azb.recordcard.audio

import java.io.File
import java.io.RandomAccessFile

data class WavInfo(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataBytes: Long,
    val durationMs: Long,
) {
    val isAdpcm: Boolean get() = audioFormat == 0x11
    val formatLabel: String
        get() = when (audioFormat) {
            1 -> "PCM"
            0x11 -> "IMA ADPCM"
            0x55 -> "MP3"
            else -> "0x%02X".format(audioFormat)
        }

    val summary: String
        get() = "$formatLabel · ${channels}ch · ${sampleRate / 1000f}kHz"
}

/**
 * Lightweight RIFF/WAVE header reader used to show duration and format
 * without pulling in a media library. Handles PCM and IMA ADPCM (the two
 * formats the recording card currently produces).
 */
object WavReader {
    fun read(file: File): WavInfo? {
        if (!file.isFile || file.length() < 44) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(12)
                if (raf.read(riff) != 12) return null
                if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
                if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null

                var format = 0
                var channels = 0
                var sampleRate = 0
                var byteRate = 0
                var blockAlign = 0
                var bits = 0
                var dataBytes = 0L
                var foundFmt = false
                var foundData = false

                var guard = 0
                while (!(foundFmt && foundData) && guard++ < 64) {
                    val header = ByteArray(8)
                    if (raf.read(header) != 8) break
                    val id = String(header, 0, 4, Charsets.US_ASCII)
                    val size = leInt(header, 4).toLong() and 0xFFFFFFFFL
                    when (id) {
                        "fmt " -> {
                            if (size < 16) return null
                            val toRead = size.toInt().coerceAtMost(64)
                            val fmt = ByteArray(toRead)
                            raf.readFully(fmt)
                            format = leShort(fmt, 0)
                            channels = leShort(fmt, 2)
                            sampleRate = leInt(fmt, 4)
                            byteRate = leInt(fmt, 8)
                            blockAlign = leShort(fmt, 12)
                            bits = leShort(fmt, 14)
                            foundFmt = true
                            if (size > toRead) raf.seek(raf.filePointer + (size - toRead))
                        }
                        "data" -> {
                            dataBytes = size
                            foundData = true
                            // Do not read audio data.
                        }
                        else -> raf.seek(raf.filePointer + size)
                    }
                    if (size % 2L != 0L) raf.seek(raf.filePointer + 1)
                }

                if (!foundFmt || !foundData || channels <= 0 || sampleRate <= 0) return null
                val durationMs = durationMs(
                    audioFormat = format,
                    channels = channels,
                    sampleRate = sampleRate,
                    byteRate = byteRate,
                    blockAlign = blockAlign,
                    dataBytes = dataBytes,
                )
                WavInfo(format, channels, sampleRate, bits, dataBytes, durationMs)
            }
        }.getOrNull()
    }

    fun durationMs(
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        byteRate: Int,
        blockAlign: Int,
        dataBytes: Long,
    ): Long {
        if (sampleRate <= 0 || dataBytes <= 0) return 0
        return when {
            audioFormat == 0x11 && blockAlign > 4 * channels -> {
                val samplesPerBlock = ((blockAlign - 4 * channels) * 2 / channels) + 1
                val blocks = dataBytes / blockAlign
                blocks * samplesPerBlock * 1000L / sampleRate
            }
            byteRate > 0 -> dataBytes * 1000L / byteRate
            else -> 0L
        }
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
