package com.azb.recordcard.protocol

import com.azb.recordcard.audio.WavReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun crc16KnownVector() {
        assertEquals(0x31C3, Crc16.xmodem("123456789".toByteArray()))
    }

    @Test
    fun frameRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = TransferFrameCodec.encode(seq = 0x1234, cmd = 0x00, payload = payload)
        val decoded = TransferFrameCodec.decode(encoded)
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(0x1234, decoded.seq)
        assertEquals(0x00, decoded.cmd)
        assertTrue(decoded.crcValid)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun frameRejectsCorruptedCrc() {
        val encoded = TransferFrameCodec.encode(seq = 1, cmd = 0x00, payload = byteArrayOf(9, 9))
        encoded[encoded.size - 1] = (encoded.last().toInt() xor 0x55).toByte()
        val decoded = TransferFrameCodec.decode(encoded)
        assertNotNull(decoded)
        assertFalse(requireNotNull(decoded).crcValid)
    }

    /** 0x07 的载荷是 ack_seq（期望的下一个 DATA 序号），小端 2B —— 不是窗口计数。 */
    @Test
    fun transferAckCarriesLittleEndianAckSeq() {
        val ack = Cmd.transferAck(0x1234)
        assertEquals(3, ack.size)
        assertEquals(0x07, ack[0].toInt() and 0xFF)
        assertEquals(0x34, ack[1].toInt() and 0xFF)
        assertEquals(0x12, ack[2].toInt() and 0xFF)
    }

    @Test
    fun transferAckAcceptsSeqWrapAround() {
        val ack = Cmd.transferAck(0xFFFF)
        assertEquals(0xFF, ack[1].toInt() and 0xFF)
        assertEquals(0xFF, ack[2].toInt() and 0xFF)
    }

    @Test
    fun decodesResumeFileStart() {
        val id = "20260717_120000"
        val payload = byteArrayOf(0xFF.toByte(), 0, 0, 0x10, 0x20) + id.toByteArray()
        val frame = TransferFrameCodec.decode(
            TransferFrameCodec.encode(seq = 0, cmd = 0x01, payload = payload),
        )
        val decoded = requireNotNull(frame).payload
        assertEquals(0xFF, decoded[0].toInt() and 0xFF)
        assertEquals(4128L, ((decoded[1].toLong() and 0xFF) shl 24) or
            ((decoded[2].toLong() and 0xFF) shl 16) or
            ((decoded[3].toLong() and 0xFF) shl 8) or
            (decoded[4].toLong() and 0xFF))
        assertEquals(id, String(decoded, 5, decoded.size - 5))
    }

    @Test
    fun parsesFullStatus() {
        val json = """
            {"recording":true,"recording_paused":false,"recording_s":42,"rec_mode":1,
             "rec_ch":2,"rec_sr":16000,"ble_conn":true,"wifi_ap":false,"ota":"idle",
             "battery":{"voltage_mv":4100,"soc_pct":85,"temp_c":28,"charging":true,"low":false},
             "storage":{"total_kb":1000,"free_kb":250},"pending_files":3,"pending_bytes":123456,
             "transfer":{"active":true,"type":"ble","file_id":"20260717_120000","file_idx":1,
                         "file_cnt":3,"bytes_done":1024,"bytes_total":4096}}
        """.trimIndent()

        val message = StatusParser.parse(json)
        assertTrue(message is StatusMessage.Full)
        val status = (message as StatusMessage.Full).status
        assertTrue(status.recording)
        assertEquals(42, status.recordingSeconds)
        assertEquals(85, status.battery?.socPct)
        assertTrue(status.battery?.charging == true)
        assertEquals(750L, status.storage?.usedBytes?.div(1024))
        assertEquals(3, status.pendingFiles)
        assertTrue(status.transfer.active)
        assertEquals("20260717_120000", status.transfer.fileId)
        assertEquals(4096L, status.transfer.bytesTotal)
    }

    @Test
    fun parsesCommandReplyAndApPatch() {
        val reply = StatusParser.parse("""{"cmd_rsp":{"ok":false,"msg":"cannot enable AP while recording"}}""")
        assertTrue(reply is StatusMessage.Reply)
        assertEquals("cannot enable AP while recording", (reply as StatusMessage.Reply).reply.message)
        assertFalse(reply.reply.ok)

        val ap = StatusParser.parse("""{"ap_enabled":true,"ap_ssid":"RecordingCard-AB12","ap_ip":"192.168.4.1"}""")
        assertTrue(ap is StatusMessage.Ap)
        assertEquals("RecordingCard-AB12", (ap as StatusMessage.Ap).info.ssid)

        val storage = StatusParser.parse("""{"storage":{"total_kb":100,"free_kb":40}}""")
        assertTrue(storage is StatusMessage.StoragePatch)
        assertEquals(60L, (storage as StatusMessage.StoragePatch).storage?.usedBytes?.div(1024))

        val mode = StatusParser.parse("""{"rec_mode":4,"rec_ch":2,"rec_sr":8000}""")
        assertTrue(mode is StatusMessage.RecModePatch)
        assertEquals(8000, (mode as StatusMessage.RecModePatch).sampleRate)
    }

    @Test
    fun rejectsNonJson() {
        assertNull(StatusParser.parse("hello"))
    }

    @Test
    fun assemblesChunkedFileList() {
        val assembler = FileListAssembler()
        val json = """[{"id":"20260717_120000","size":123456},{"id":"20260717_120500","size":78901}]"""
        val first = assembler.append(json.substring(0, 18))
        assertNull(first)
        val second = assembler.append(json.substring(18, 45))
        assertNull(second)
        val third = assembler.append(json.substring(45))
        assertNotNull(third)
        val result = requireNotNull(third)
        assertEquals(2, result.files.size)
        assertFalse(result.isFullList)
        assertEquals(123456L, result.files[0].size)

        val full = assembler.append("""[{"id":"a","size":1,"uploaded":true}]""")
        assertTrue(requireNotNull(full).isFullList)
        assertTrue(requireNotNull(full).files[0].uploaded)
    }

    @Test
    fun emptyFileListIsValid() {
        val result = FileListAssembler.parse("[]")
        assertNotNull(result)
        assertTrue(requireNotNull(result).files.isEmpty())
    }

    @Test
    fun parsesDeviceIdentityJson() {
        val obj = JSONObject("""{"id":"RC1-202610-00001-A","key":"secret"}""")
        assertEquals("RC1-202610-00001-A", obj.getString("id"))
    }

    @Test
    fun computesWavDurations() {
        // PCM16 stereo 16 kHz -> 64000 B/s
        assertEquals(1000L, WavReader.durationMs(1, 2, 16_000, 64_000, 4, 64_000))

        // IMA ADPCM: blockAlign 512, 2ch -> (512-8)*2/2+1 = 505 samples per block
        assertEquals(63L, WavReader.durationMs(0x11, 2, 16_000, 0, 512, 1024))
    }

    @Test
    fun normalizeIds() {
        assertEquals("20260717_120000", FileIds.normalize("pending_20260717_120000"))
        assertEquals("20260717_120000", FileIds.normalize("uploaded_20260717_120000.wav"))
        assertEquals("20260717_120000", FileIds.normalize("20260717_120000"))
        assertEquals("20260717 120000", FileIds.display("20260717_120000"))
    }
}
