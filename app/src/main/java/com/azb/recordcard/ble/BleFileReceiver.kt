package com.azb.recordcard.ble

import com.azb.recordcard.core.AppLog
import com.azb.recordcard.data.LocalLibrary
import com.azb.recordcard.protocol.TransferFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives 0xA103 transfer frames, writes them to disk and emits the flow
 * control ACK (0x07 + 2B LE `ack_seq`) every protocol window of 12 DATA frames.
 *
 * 流控约定（与固件 `transfer_frame.h` / `ble_file_transfer_service.cc` 对齐）：
 * 设备每发完一窗（[WINDOW_SIZE] 帧）就停下等一个 0x07；收到才允许再发一窗。
 * 因此丢一个 ACK 就等于设备干等 10s 后 `Credit timeout` 判失败 —— ACK 的发送
 * 必须"要么成功、要么补发"，不能静默丢弃（见 [sendWindowAck]）。
 */
class BleFileReceiver(
    private val library: LocalLibrary,
    private val scope: CoroutineScope,
    private val sendAck: suspend (ackSeq: Int) -> Boolean,
    private val deviceProvider: () -> Pair<String?, String?>?,
    private val listener: Listener,
) {
    interface Listener {
        fun onFileStart(fileId: String, offset: Long, expectedSize: Long?)
        fun onProgress(fileId: String, bytes: Long, total: Long?, speedBps: Long)
        fun onFileComplete(fileId: String, extension: String, size: Long)
        fun onFailure(message: String)
        fun onWarning(message: String)

        /**
         * 设备的续传基点（FILE_START 里的 offset）**大于**本地分片长度。
         *
         * 此时本地缺了一段不可恢复的数据，只能整文件重传：接收侧已经丢弃分片并
         * 停止接收，实现方需要让设备退出本次续传（0x06 / TRANSFER_STOP），
         * 再重新发起一次完整传输。**绝不能**用 0 补齐本地文件 —— FILE_END 的总长
         * 是绝对值，补零后长度校验会通过，坏文件会被当成正常录音保存下来。
         */
        fun onResumeMismatch(fileId: String, localBytes: Long, deviceOffset: Long)
    }

    private sealed interface Message {
        data class Frame(val data: ByteArray) : Message
        data class Abort(val deletePartial: Boolean) : Message
        data object Reset : Message
    }

    private val queue = Channel<Message>(QUEUE_CAPACITY)
    private val overflowReported = AtomicBoolean(false)
    private var raf: RandomAccessFile? = null
    private var partFile: File? = null
    private var currentId: String? = null
    private var bytes = 0L
    private var ackCounter = 0
    private var expectedTotal: Long? = null
    private var lastSeq = -1
    private var lastDataSeq = -1
    private var ackFailCount = 0
    private var lastReportAt = 0L
    private var lastReportBytes = 0L
    private var speedBps = 0L

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var accepting = true

    /** 已放弃当前文件（续传基点不一致），后续 DATA 帧静默丢弃，等上层发 STOP。 */
    @Volatile
    private var discardFrames = false

    @Volatile
    var currentFileId: String? = null
        private set

    @Volatile
    var currentBytes: Long = 0L
        private set

    private var sizeLookup: ((String) -> Long?)? = null

    init {
        scope.launch(Dispatchers.IO) { consume() }
    }

    fun onSubmitFrame(data: ByteArray) {
        if (!accepting) return
        if (queue.trySend(Message.Frame(data)).isFailure) {
            // 队列只在"写盘卡死"时才会满：设备侧有 credit 流控，在途最多 2 窗（24 帧），
            // 容量给了 2 倍以上余量。真满了就按失败处理（保留分片供续传），
            // 绝不静默丢帧 —— 丢一帧就会让 FILE_END 的长度校验对不上，等于白传。
            if (overflowReported.compareAndSet(false, true)) {
                AppLog.e("Transfer", "接收队列溢出（写盘跟不上），中止本次传输")
                listener.onFailure("接收队列溢出：写盘速度跟不上，本次传输已中止")
                scope.launch { queue.send(Message.Abort(deletePartial = false)) }
            }
        }
    }

    fun setSizeLookup(lookup: (String) -> Long?) {
        sizeLookup = lookup
    }

    /** Stops the current file. Partial data is kept when [deletePartial] is false. */
    fun abort(deletePartial: Boolean = false) {
        accepting = false
        // 控制消息不能因为队列满而丢掉（否则文件句柄不会被关、分片也不会按预期处理）；
        // 队列有界只针对高吞吐的 Frame，控制消息满了就排队等空位。
        if (queue.trySend(Message.Abort(deletePartial)).isFailure) {
            scope.launch { queue.send(Message.Abort(deletePartial)) }
        }
    }

    fun reset() {
        accepting = true
        if (queue.trySend(Message.Reset).isFailure) {
            scope.launch { queue.send(Message.Reset) }
        }
    }

    private suspend fun consume() {
        for (message in queue) {
            when (message) {
                is Message.Frame -> processFrame(message.data)
                is Message.Abort -> stopInternal(message.deletePartial)
                Message.Reset -> stopInternal(deletePartial = false, silent = true)
            }
        }
    }

    private suspend fun processFrame(data: ByteArray) {
        val frame = TransferFrameCodec.decode(data)
        if (frame == null) {
            listener.onWarning("帧长度异常 (${data.size}B)")
            return
        }
        if (!frame.crcValid) {
            listener.onWarning("CRC 校验失败 seq=${frame.seq}")
            return
        }
        // 已放弃本次文件（续传基点不一致）：把设备这一轮的 DATA 安静丢掉，
        // 不再每帧刷警告，等上层送达 0x06/STOP 让设备退出。
        if (discardFrames && frame.cmd == TransferFrameCodec.CMD_DATA) return
        if (frame.isData && lastSeq >= 0 && ((lastSeq + 1) and 0xFFFF) != frame.seq) {
            listener.onWarning("帧序号不连续: ${lastSeq} -> ${frame.seq}")
        }
        lastSeq = frame.seq
        if (frame.cmd == TransferFrameCodec.CMD_DATA) lastDataSeq = frame.seq
        when (frame.cmd) {
            TransferFrameCodec.CMD_FILE_START -> handleStart(frame.payload)
            TransferFrameCodec.CMD_DATA -> handleData(frame.payload)
            TransferFrameCodec.CMD_FILE_END -> handleEnd(frame.payload)
            TransferFrameCodec.CMD_ERROR -> {
                val text = String(frame.payload, StandardCharsets.UTF_8)
                listener.onFailure("设备返回错误: $text")
                stopInternal(deletePartial = false)
            }
            else -> listener.onWarning("未知帧命令 0x%02X".format(frame.cmd))
        }
    }

    private suspend fun handleStart(payload: ByteArray) {
        discardFrames = false
        overflowReported.set(false)
        val resume = payload.isNotEmpty() && payload[0] == 0xFF.toByte() && payload.size >= 5
        val offset: Long
        val id: String
        if (resume) {
            offset = beUInt(payload, 1)
            id = String(payload, 5, payload.size - 5, StandardCharsets.UTF_8)
        } else {
            offset = 0
            id = String(payload, StandardCharsets.UTF_8)
        }
        if (id.isBlank()) {
            listener.onFailure("FILE_START 缺少文件 ID")
            return
        }
        closeQuietly()
        currentId = id
        currentBytes = offset
        bytes = offset
        ackCounter = 0
        lastSeq = -1
        lastDataSeq = -1
        ackFailCount = 0
        expectedTotal = sizeLookup?.invoke(id)?.takeIf { it > 0 }
        val part = library.partialFile(id, "ble")
        partFile = part
        var mismatch: Pair<Long, Long>? = null   // (本地长度, 设备续传基点)
        try {
            val file = RandomAccessFile(part, "rw")
            val localLength = file.length()
            if (offset > 0) {
                // 设备的续传基点是它"已确认收到的窗口末尾"，通常 ≤ 本地已写长度，
                // 差的最后不满一窗会被重发，所以截断到 offset 是正确且必须的。
                if (localLength > offset) {
                    file.setLength(offset)
                } else if (localLength < offset) {
                    // ⚠️ 这里以前是 setLength(offset) —— 中间补 0。
                    // 补零会骗过 handleEnd 的 `part.length() != total` 校验，
                    // 把一段"长度正常、内容全空"的文件 commit 成正常录音。
                    // 本地缺了不可恢复的数据，只能丢弃分片、整文件重传。
                    file.close()
                    part.delete()
                    mismatch = localLength to offset
                }
            } else {
                file.setLength(0)
            }
            if (mismatch == null) {
                file.seek(offset)
                raf = file
            }
        } catch (e: Exception) {
            listener.onFailure("无法创建本地文件: ${e.message}")
            return
        }
        mismatch?.let { (localLength, deviceOffset) ->
            resetState()
            discardFrames = true
            AppLog.w(
                "Transfer",
                "续传基点不一致 id=$id 本地=${localLength}B 设备=$deviceOffset B — 丢弃分片，改为整文件重传",
            )
            listener.onResumeMismatch(id, localLength, deviceOffset)
            return
        }
        active = true
        lastReportAt = System.currentTimeMillis()
        lastReportBytes = offset
        speedBps = 0
        listener.onFileStart(id, offset, expectedTotal)
        AppLog.i("Transfer", "FILE_START $id offset=$offset")
    }

    private suspend fun handleData(payload: ByteArray) {
        val file = raf
        if (file == null) {
            listener.onWarning("收到 DATA 但当前没有打开的文件")
            return
        }
        try {
            file.write(payload)
        } catch (e: Exception) {
            listener.onFailure("写入本地文件失败: ${e.message}")
            stopInternal(deletePartial = false)
            return
        }
        bytes += payload.size
        currentBytes = bytes
        ackCounter++
        val now = System.currentTimeMillis()
        if (now - lastReportAt >= 250) {
            val deltaBytes = bytes - lastReportBytes
            val deltaMs = (now - lastReportAt).coerceAtLeast(1)
            speedBps = deltaBytes * 1000 / deltaMs
            lastReportAt = now
            lastReportBytes = bytes
            listener.onProgress(currentId ?: "", bytes, expectedTotal, speedBps)
        }
        if (ackCounter >= WINDOW_SIZE) {
            sendWindowAck()
        }
    }

    private suspend fun handleEnd(payload: ByteArray) {
        val total = if (payload.size >= 4) beUInt(payload, 0) else bytes
        if (ackCounter > 0) {
            // 收尾的不满一窗：设备已经走到 EOF，不靠这个信用继续发，
            // 所以只做一次尽力而为的发送（不重试、不阻塞落盘）。
            ackCounter = 0
            val ackSeq = nextDataSeq()
            scope.launch { runCatching { sendAck(ackSeq) } }
        }
        // 必须先 sync 再 close：设备收到 0x07 就认为这一段"已确认"，页缓存里的
        // 数据掉电即丢；close 之后 fd 就不能再用了。
        syncQuietly()
        closeQuietly()
        val part = partFile
        val id = currentId
        if (part == null || id == null) {
            listener.onFailure("FILE_END 时缺少文件上下文")
            stopInternal(deletePartial = false)
            return
        }
        val actual = part.length()
        listener.onProgress(id, actual, total, speedBps)
        if (actual != total) {
            listener.onFailure("文件大小不一致：本地 $actual B，设备 $total B")
            part.delete()
            resetState()
            return
        }
        val ext = detectExtension(part)
        val device = deviceProvider()
        val record = library.commit(part, id, ext, device?.first, device?.second)
        if (record == null) {
            listener.onFailure("保存录音失败")
            part.delete()
            resetState()
            return
        }
        AppLog.i("Transfer", "文件接收完成: ${record.file.name} ${record.size}B")
        listener.onFileComplete(id, ext, actual)
        resetState()
    }

    /**
     * 发一窗的流控 ACK，失败则有界重试/补发。
     *
     * 为什么这里是"内联重试"而不是"定时器 + 异步补发"：
     * [ackCounter] 攒满一窗，说明设备已经发完这一窗、**正在等这个信用**——
     * 它在拿到信用之前不会再发任何 DATA 帧。所以在这段窗口里阻塞消费协程
     * 是安全的（不会有新帧被漏掉），也就顺带省掉了跨线程的
     * `ackSending`/`fileGen` 那套状态机，不存在"旧文件的在途 ACK 扣新文件计数"。
     *
     * 重试预算：立即再试 1 次（30ms）→ 之后每 250ms 补一次、最多 3 次，
     * 总耗时约 1 秒（上限 [ACK_BUDGET_MS] 兜住写入超时叠加），
     * 远小于设备侧 10 秒的 credit 超时。
     *
     * ⚠️ 不做"重试到成功"：多发一个 ACK 就是多一窗信用。新固件把信用封顶在
     * 2 个窗口（多余的会被 `Credit dropped` 丢弃），老固件信用无上限，
     * 会直接把 msys 池灌爆 —— 所以只在**明确失败**时补发，成功即止。
     */
    private suspend fun sendWindowAck() {
        val ackSeq = nextDataSeq()
        val deadline = System.currentTimeMillis() + ACK_BUDGET_MS
        var ok = writeAck(ackSeq)
        if (!ok) {
            delay(30)
            ok = writeAck(ackSeq)
        }
        var retries = 0
        while (!ok && retries < ACK_RETRY_MAX && System.currentTimeMillis() < deadline) {
            retries++
            delay(250)
            ok = writeAck(ackSeq)
        }
        if (ok) {
            // 只在成功后扣减，且是按窗扣（不是清零）：失败期间若又攒了几帧，
            // 那些帧的信用要留在下一窗里，不能一起丢掉。
            ackCounter = (ackCounter - WINDOW_SIZE).coerceAtLeast(0)
            if (ackFailCount > 0) {
                AppLog.i("Transfer", "ACK 恢复 ack_seq=$ackSeq 之前累计失败=$ackFailCount")
                ackFailCount = 0
            }
            return
        }
        ackFailCount++
        if (ackFailCount <= 3 || ackFailCount % 100 == 0) {
            AppLog.w(
                "Transfer",
                "ACK 发送失败(限流日志) ack_seq=$ackSeq 待确认帧数=$ackCounter 累计=$ackFailCount",
            )
        }
        // 计数保留：下一帧 DATA 会自然再次触发；若设备就此停住（永远没有下一帧），
        // 只能靠设备侧 10s credit 超时兜底 —— 这条路径本身没有任何东西可再补救。
    }

    private suspend fun writeAck(ackSeq: Int): Boolean =
        runCatching { sendAck(ackSeq) }.getOrDefault(false)

    /** 期望设备下一次发来的 DATA 序号。取最后一帧 **DATA** 的 seq，
     *  不能用 [lastSeq]（FILE_END 帧也会更新它，那样算出来是 FILE_END+1）。 */
    private fun nextDataSeq(): Int = (lastDataSeq + 1) and 0xFFFF

    private fun detectExtension(file: File): String {
        return try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(4)
                if (f.read(head) == 4) {
                    val magic = String(head, StandardCharsets.US_ASCII)
                    when {
                        magic == "OggS" -> "ogg"
                        magic == "RIFF" -> "wav"
                        else -> "wav"
                    }
                } else {
                    "wav"
                }
            }
        } catch (e: Exception) {
            "wav"
        }
    }

    private fun stopInternal(deletePartial: Boolean, silent: Boolean = false) {
        val hadFile = currentId != null
        closeQuietly()
        if (deletePartial) {
            partFile?.delete()
        }
        if (hadFile && !silent) {
            AppLog.i("Transfer", "接收中止 ${currentId ?: ""}（保留分片=${!deletePartial}）")
        }
        resetState()
    }

    private fun resetState() {
        active = false
        currentId = null
        currentBytes = 0
        bytes = 0
        ackCounter = 0
        expectedTotal = null
        lastSeq = -1
        lastDataSeq = -1
        ackFailCount = 0
        discardFrames = false
        lastReportAt = 0
        lastReportBytes = 0
        partFile = null
        speedBps = 0
    }

    private fun closeQuietly() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
    }

    private fun syncQuietly() {
        try {
            raf?.fd?.sync()
        } catch (e: Exception) {
            AppLog.w("Transfer", "落盘同步失败: ${e.message}")
        }
    }

    /** Information needed to offer a resume after a dropped connection. */
    data class Partial(val fileId: String, val bytes: Long)

    fun partial(): Partial? {
        val id = currentFileId ?: return null
        val part = library.partialFile(id, "ble")
        if (!part.isFile) return null
        return Partial(id, currentBytes)
    }

    private fun beUInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    companion object {
        /**
         * **协议常量**：必须与固件 `main/network/transfer_frame.h` 的 `kWndSize` 一致
         * （三方固件/App/网页各自硬编码 12）。任何一侧要改，必须先做能力协商，
         * 否则流控会立刻错位（改小 → 设备多发不算信用；改大 → App 少发 ACK → 卡住）。
         */
        const val WINDOW_SIZE = 12

        /** 一窗 ACK 的总重试预算，必须远小于设备侧 10s 的 credit 超时。 */
        private const val ACK_BUDGET_MS = 2_000L

        /** 失败后补发次数上限（配合 250ms 间隔 ≈ 750ms）。 */
        private const val ACK_RETRY_MAX = 3

        /**
         * 待写盘帧的缓冲深度。
         *
         * 设备侧一次只放 2 个窗口的 credit（在途 ≤ 24 帧），64 已是 2.5× 余量；
         * 满了说明写盘真的卡住了，此时宁可失败重传，也不能无界堆积吃光内存。
         */
        private const val QUEUE_CAPACITY = 64
    }
}
