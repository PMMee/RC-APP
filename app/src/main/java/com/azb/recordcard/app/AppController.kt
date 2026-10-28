package com.azb.recordcard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.azb.recordcard.audio.AudioPlayer
import com.azb.recordcard.audio.PlayerState
import com.azb.recordcard.ble.BleConnection
import com.azb.recordcard.ble.BleConnectionState
import com.azb.recordcard.ble.BleEvent
import com.azb.recordcard.ble.BleFileReceiver
import com.azb.recordcard.ble.BleScanner
import com.azb.recordcard.ble.BleUuids
import com.azb.recordcard.ble.ScanDevice
import com.azb.recordcard.core.AppLog
import com.azb.recordcard.core.LogEntry
import com.azb.recordcard.core.TransferNotifications
import com.azb.recordcard.data.AppSettings
import com.azb.recordcard.data.DeviceStore
import com.azb.recordcard.data.LocalLibrary
import com.azb.recordcard.data.LocalRecording
import com.azb.recordcard.data.SavedDevice
import com.azb.recordcard.protocol.ApInfo
import com.azb.recordcard.protocol.BatteryInfo
import com.azb.recordcard.protocol.Cmd
import com.azb.recordcard.protocol.DeviceStatus
import com.azb.recordcard.protocol.FileIds
import com.azb.recordcard.protocol.FileListAssembler
import com.azb.recordcard.protocol.Identity
import com.azb.recordcard.protocol.RemoteFile
import com.azb.recordcard.protocol.StorageInfo
import com.azb.recordcard.protocol.StatusMessage
import com.azb.recordcard.protocol.StatusParser
import com.azb.recordcard.protocol.TransferInfo
import com.azb.recordcard.wifi.DeviceHttpClient
import com.azb.recordcard.wifi.WifiApConnector
import com.azb.recordcard.wifi.WifiConnectResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.TimeZone
import java.util.UUID

/**
 * Application-scoped orchestrator: BLE connection, protocol commands,
 * transfers (BLE and WiFi), persistence, local library and playback.
 */
class AppController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = DeviceStore(app)
    private val library = LocalLibrary(app)
    private val wifi = WifiApConnector(app)
    private val player = AudioPlayer(scope)

    private val _devices = MutableStateFlow(store.loadDevices())
    val devices: StateFlow<List<SavedDevice>> = _devices.asStateFlow()

    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _recordings = MutableStateFlow<List<LocalRecording>>(emptyList())
    val recordings: StateFlow<List<LocalRecording>> = _recordings.asStateFlow()

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ap = MutableStateFlow(ApState())
    val ap: StateFlow<ApState> = _ap.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val logs: StateFlow<List<LogEntry>> = AppLog.entries
    val playerState: StateFlow<PlayerState> = player.state

    private val fileListAssembler = FileListAssembler()
    private val scanDevices = LinkedHashMap<String, ScanDevice>()
    private var listResetJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var apJob: Job? = null
    private var resumeRecoveryJob: Job? = null

    private var expectFullList = false
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var autoSyncArmed = true
    private var autoSyncInFlight = false
    private var apStopping = false
    private var apFailure: String? = null
    private var manualWifiDeferred: CompletableDeferred<Network?>? = null

    private val ble = BleConnection(app, scope) { event -> onBleEvent(event) }

    private val receiver = BleFileReceiver(
        library = library,
        scope = scope,
        sendAck = { ackSeq ->
            ble.write(BleUuids.CHAR_CONTROL, Cmd.transferAck(ackSeq), withResponse = false)
        },
        deviceProvider = {
            _session.value?.let { it.address to it.name }
        },
        listener = object : BleFileReceiver.Listener {
            override fun onFileStart(fileId: String, offset: Long, expectedSize: Long?) {
                scope.launch { handleFileStart(fileId, offset, expectedSize) }
            }

            override fun onProgress(fileId: String, bytes: Long, total: Long?, speedBps: Long) {
                scope.launch { handleFileProgress(fileId, bytes, total, speedBps) }
            }

            override fun onFileComplete(fileId: String, extension: String, size: Long) {
                scope.launch { handleFileComplete(fileId, extension, size) }
            }

            override fun onFailure(message: String) {
                scope.launch { handleTransferFailure(message) }
            }

            override fun onWarning(message: String) {
                AppLog.w("Transfer", message)
            }

            override fun onResumeMismatch(fileId: String, localBytes: Long, deviceOffset: Long) {
                handleResumeMismatch(fileId, localBytes, deviceOffset)
            }
        },
    )

    private val scanner = BleScanner(
        context = app,
        onDevice = { device -> onScanDevice(device) },
        onError = { message ->
            _scan.update { it.copy(running = false, error = message) }
            showMessage(message)
        },
    )

    init {
        library.ensureDirectories()
        library.cleanupPartials(PARTIAL_MAX_AGE_MS)
        unbindProcessNetwork()
        refreshLibrary()
        receiver.setSizeLookup { id ->
            _session.value?.pendingFiles?.firstOrNull { it.id == id }?.size
        }
    }

    // ---------------------------------------------------------------- devices

    fun selectDevice(address: String) {
        val device = _devices.value.firstOrNull { it.address == address } ?: return
        if (isDemoAddress(address)) {
            if (_session.value?.address != address) {
                stopScan()
                _session.value = demoSession(device)
            }
            return
        }
        val current = _session.value
        if (current?.address == address) {
            if (ble.connectedAddress == address && current.phase != ConnPhase.FAILED) {
                if (current.phase == ConnPhase.DISCONNECTED) connect(address)
                return
            }
        } else if (current != null) {
            manualDisconnect = true
            if (ble.connectedAddress != null) ble.disconnect()
            receiver.abort(deletePartial = true)
            cancelApSession()
        }
        stopScan()
        _session.value = SessionState(address = address, name = device.displayName)
        manualDisconnect = false
        reconnectAttempts = 0
        connect(address)
    }

    fun connect(address: String? = null) {
        val target = address ?: _session.value?.address ?: return
        val device = _devices.value.firstOrNull { it.address == target } ?: return
        if (isDemoAddress(target)) {
            _session.value = demoSession(device)
            return
        }
        manualDisconnect = false
        reconnectAttempts = 0
        val base = _session.value ?: SessionState(target, device.displayName)
        _session.value = base.copy(
            address = target,
            name = device.displayName,
            phase = ConnPhase.CONNECTING,
            note = "正在连接…",
        )
        ble.connect(target)
    }

    fun disconnect() {
        if (_session.value?.demo == true) {
            updateSession { it.copy(phase = ConnPhase.DISCONNECTED, note = "演示设备已断开") }
            return
        }
        manualDisconnect = true
        cancelApSession()
        receiver.abort(deletePartial = true)
        ble.disconnect()
        TransferNotifications.cancel(app)
        updateSession {
            it.copy(
                phase = ConnPhase.DISCONNECTED,
                note = "已断开连接",
                transfer = null,
                pendingResume = null,
            )
        }
    }

    fun forgetDevice(address: String) {
        if (_session.value?.address == address) {
            disconnect()
            _session.value = null
        }
        _devices.update { list -> list.filterNot { it.address == address } }
        store.saveDevices(_devices.value)
        showMessage("已移除设备")
    }

    fun addDeviceAndConnect(name: String, address: String) {
        val existing = _devices.value.firstOrNull { it.address == address }
        if (existing == null) {
            val defaults = _settings.value
            val device = SavedDevice(
                address = address,
                name = name,
                autoSync = defaults.autoSyncDefault,
                preferHighSpeed = defaults.preferHighSpeedDefault,
            )
            _devices.update { it + device }
            store.saveDevices(_devices.value)
        } else if (name.isNotBlank() && existing.name != name) {
            updateDevice(address) { it.copy(name = name) }
        }
        stopScan()
        selectDevice(address)
    }

    // -------------------------------------------------------------- BLE events

    private fun onBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Notification -> {
                if (event.characteristic == BleUuids.CHAR_FILE_DATA) {
                    receiver.onSubmitFrame(event.value)
                } else {
                    scope.launch { handleNotification(event.characteristic, event.value) }
                }
            }

            else -> scope.launch { handleBleEvent(event) }
        }
    }

    private suspend fun handleBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.State -> {
                if (event.address != null && event.address != _session.value?.address) {
                    AppLog.d(TAG, "忽略旧连接事件 ${event.address} -> ${event.state}")
                    return
                }
                when (event.state) {
                    BleConnectionState.CONNECTING ->
                        updateSession { it.copy(phase = ConnPhase.CONNECTING, note = event.message) }

                    BleConnectionState.DISCOVERING ->
                        updateSession { it.copy(phase = ConnPhase.DISCOVERING, note = event.message) }

                    BleConnectionState.READY -> Unit

                    BleConnectionState.DISCONNECTED -> handleDisconnected(event.message)

                    BleConnectionState.FAILED -> handleConnectFailed(event.message)
                }
            }

            is BleEvent.Ready -> {
                if (event.address != null && event.address != _session.value?.address) return
                handleReady(event.mtu)
            }

            is BleEvent.Rssi -> updateSession { it.copy(rssi = event.rssi) }

            is BleEvent.Notification -> Unit
        }
    }

    private suspend fun handleReady(mtu: Int) {
        reconnectAttempts = 0
        autoSyncArmed = true
        updateSession {
            it.copy(
                phase = ConnPhase.READY,
                mtu = mtu,
                note = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val address = _session.value?.address
        if (address != null) {
            updateDevice(address) { it.copy(lastConnectedAt = System.currentTimeMillis()) }
        }
        showMessage("已连接 ${_session.value?.name.orEmpty()}")
        delay(200)
        sendTimeSync()
        readStatus()
        readFileList()
        readIdentity()
        delay(1_200)
        readStatus()
        readRssi()
        maybeAutoSync()
    }

    private fun handleDisconnected(message: String?) {
        val s = _session.value ?: return
        if (s.demo) return
        val wasReady = s.phase == ConnPhase.READY || s.phase == ConnPhase.DISCOVERING
        val partial = receiver.partial()
        receiver.abort(deletePartial = false)
        cancelApSession()
        autoSyncInFlight = false
        updateSession {
            it.copy(
                phase = ConnPhase.DISCONNECTED,
                note = message ?: "连接已断开",
                transfer = null,
                pendingResume = partial?.let { p -> PartialTransfer(p.fileId, p.bytes) } ?: it.pendingResume,
            )
        }
        AppLog.w(TAG, "连接断开: ${message ?: ""}")
        TransferNotifications.cancel(app)
        if (wasReady && !manualDisconnect) scheduleReconnect()
    }

    private fun handleConnectFailed(message: String?) {
        updateSession {
            it.copy(
                phase = ConnPhase.FAILED,
                note = message ?: "连接失败",
                transfer = null,
            )
        }
        if (!manualDisconnect) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            updateSession { it.copy(note = "自动重连失败，请点击重新连接") }
            return
        }
        reconnectAttempts++
        val attempt = reconnectAttempts
        val address = _session.value?.address ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1_500L * attempt)
            val current = _session.value ?: return@launch
            if (current.address != address || manualDisconnect) return@launch
            if (current.phase == ConnPhase.READY || current.phase == ConnPhase.CONNECTING) return@launch
            AppLog.i(TAG, "自动重连第 $attempt 次")
            updateSession { it.copy(phase = ConnPhase.CONNECTING, note = "正在重新连接…") }
            ble.connect(address)
        }
    }

    // ------------------------------------------------------------ notifications

    private fun handleNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            BleUuids.CHAR_STATUS -> handleStatusPayload(value)
            BleUuids.CHAR_FILE_LIST -> handleFileListChunk(value)
            BleUuids.CHAR_BATTERY_LEVEL -> handleBatteryLevel(value)
            BleUuids.CHAR_OTA_STATUS -> AppLog.d("OTA", "状态帧 ${value.size}B")
            else -> AppLog.d(TAG, "通知 $characteristic ${value.size}B")
        }
    }

    private fun handleBatteryLevel(value: ByteArray) {
        val level = value.firstOrNull()?.toInt()?.and(0xFF) ?: return
        updateSession { s ->
            val status = s.status ?: return@updateSession s
            val battery = (status.battery ?: BatteryInfo()).copy(socPct = level)
            s.copy(status = status.copy(battery = battery))
        }
    }

    private fun handleStatusPayload(value: ByteArray) {
        val text = String(value, StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return
        AppLog.d("Status", text)
        val message = StatusParser.parse(text)
        if (message == null) {
            AppLog.w("Status", "无法解析的状态: ${text.take(120)}")
            return
        }
        when (message) {
            is StatusMessage.Full -> applyFullStatus(message.status)
            is StatusMessage.Reply -> {
                updateSession { it.copy(lastReply = message.reply) }
                AppLog.i("CmdRsp", "${message.reply.ok} ${message.reply.message}")
                if (!message.reply.ok) {
                    showMessage(message.reply.message.ifBlank { "命令执行失败" })
                    if (message.reply.message.contains("AP", ignoreCase = true)) {
                        apFailure = message.reply.message
                    }
                }
            }

            is StatusMessage.Ap -> {
                updateSession { it.copy(apInfo = message.info) }
                AppLog.i(
                    "AP",
                    "enabled=${message.info.enabled} ssid=${message.info.ssid} ip=${message.info.ip}",
                )
            }

            is StatusMessage.StoragePatch -> updateSession { s ->
                s.copy(status = s.status?.copy(storage = message.storage))
            }

            is StatusMessage.RecModePatch -> updateSession { s ->
                s.copy(
                    status = s.status?.copy(
                        recMode = message.mode,
                        recChannels = message.channels,
                        recSampleRate = message.sampleRate,
                    ),
                )
            }

            is StatusMessage.Unknown -> AppLog.d("Status", "未识别的 JSON: ${message.text}")
        }
    }

    private fun applyFullStatus(status: DeviceStatus) {
        val previous = _session.value?.status
        if (status.pendingFiles == 0) autoSyncArmed = true
        if (previous != null && status.pendingFiles > previous.pendingFiles) autoSyncArmed = true
        updateSession {
            it.copy(
                status = status,
                apInfo = if (status.wifiAp) it.apInfo else ApInfo(),
            )
        }
        cacheDeviceStatus(status)
        applyTransferFromStatus(status)
        if (!status.transfer.active) autoSyncInFlight = receiver.active
        maybeAutoSync()
    }

    private fun applyTransferFromStatus(status: DeviceStatus) {
        val transfer = status.transfer
        if (transfer.active) {
            val mode = if (transfer.type == "wifi") TransferMode.WIFI else TransferMode.BLE
            updateSession { s ->
                val existing = s.transfer
                val base = if (existing != null && existing.mode == mode) {
                    existing
                } else {
                    TransferView(mode = mode, startedAt = System.currentTimeMillis())
                }
                val localBytes = if (mode == TransferMode.BLE && receiver.active) {
                    maxOf(base.bytes, transfer.bytesDone)
                } else {
                    transfer.bytesDone
                }
                s.copy(
                    transfer = base.copy(
                        fileId = transfer.fileId.ifBlank { base.fileId },
                        bytes = localBytes,
                        total = transfer.bytesTotal.takeIf { it > 0 } ?: base.total,
                        fileIndex = transfer.fileIndex,
                        fileCount = transfer.fileCount,
                    ),
                )
            }
        } else if (!receiver.active) {
            updateSession { s -> if (s.transfer != null) s.copy(transfer = null) else s }
            TransferNotifications.cancel(app)
        }
    }

    private fun handleFileListChunk(value: ByteArray) {
        val text = String(value, StandardCharsets.UTF_8)
        val result = fileListAssembler.append(text)
        if (result == null) {
            scheduleListReset()
            return
        }
        listResetJob?.cancel()
        val asFull = expectFullList || result.isFullList
        expectFullList = false
        if (asFull) {
            updateSession { it.copy(allFiles = result.files, allFilesLoaded = true) }
            AppLog.i("List", "全部文件 ${result.files.size} 个")
        } else {
            updateSession { it.copy(pendingFiles = result.files) }
            AppLog.i("List", "待上传 ${result.files.size} 个")
            maybeAutoSync()
        }
    }

    private fun scheduleListReset() {
        listResetJob?.cancel()
        listResetJob = scope.launch {
            delay(2_000)
            expectFullList = false
            fileListAssembler.reset()
        }
    }

    // ------------------------------------------------------------- BLE transfer

    private fun handleFileStart(fileId: String, offset: Long, expectedSize: Long?) {
        val current = _session.value ?: return
        val statusTransfer = current.status?.transfer
        autoSyncArmed = false
        autoSyncInFlight = true
        updateSession { s ->
            s.copy(
                pendingResume = null,
                transfer = TransferView(
                    mode = TransferMode.BLE,
                    fileId = fileId,
                    bytes = offset,
                    total = expectedSize ?: statusTransfer?.bytesTotal ?: 0,
                    fileIndex = statusTransfer?.fileIndex ?: s.transfer?.fileIndex ?: 0,
                    fileCount = statusTransfer?.fileCount ?: s.transfer?.fileCount ?: 0,
                    startedAt = System.currentTimeMillis(),
                ),
            )
        }
        TransferNotifications.show(app, "录音同步", "正在接收 ${FileIds.display(fileId)}")
    }

    private fun handleFileProgress(fileId: String, bytes: Long, total: Long?, speed: Long) {
        updateSession { s ->
            val base = s.transfer ?: TransferView(
                mode = TransferMode.BLE,
                fileId = fileId,
                startedAt = System.currentTimeMillis(),
            )
            s.copy(
                transfer = base.copy(
                    mode = TransferMode.BLE,
                    fileId = fileId,
                    bytes = bytes,
                    total = total ?: base.total,
                    speedBps = speed,
                ),
            )
        }
    }

    private fun handleFileComplete(fileId: String, extension: String, size: Long) {
        AppLog.i(TAG, "完成 $fileId.$extension ${size}B")
        showMessage("已保存 ${FileIds.display(fileId)}")
        TransferNotifications.show(app, "录音同步", "已保存 ${FileIds.display(fileId)}")
        updateSession { s ->
            s.copy(
                pendingFiles = s.pendingFiles.filterNot { it.id == fileId },
                allFiles = s.allFiles.map { if (it.id == fileId) it.copy(uploaded = true) else it },
            )
        }
        refreshLibrary()
        scope.launch {
            delay(1_200)
            if (!receiver.active) {
                updateSession { s ->
                    if (s.transfer?.mode == TransferMode.BLE && s.transfer.fileId == fileId) {
                        s.copy(transfer = null)
                    } else {
                        s
                    }
                }
            }
        }
    }

    private fun handleTransferFailure(message: String) {
        AppLog.e(TAG, message)
        showMessage(message)
        TransferNotifications.cancel(app)
        autoSyncInFlight = false
        autoSyncArmed = false
        updateSession { it.copy(transfer = null) }
    }

    // --------------------------------------------------------------- commands

    private suspend fun sendCommand(data: ByteArray, withResponse: Boolean = true): Boolean {
        if (_session.value?.demo == true) {
            showMessage("演示模式：不会向真实设备发送命令")
            return false
        }
        if (!ble.isReady) return false
        AppLog.d("Cmd", hex(data))
        val ok = ble.write(BleUuids.CHAR_CONTROL, data, withResponse)
        if (!ok) AppLog.w("Cmd", "写入失败 ${hex(data)}")
        return ok
    }

    private suspend fun readStatus() {
        val value = ble.read(BleUuids.CHAR_STATUS) ?: return
        handleStatusPayload(value)
    }

    private suspend fun readFileList() {
        val value = ble.read(BleUuids.CHAR_FILE_LIST) ?: return
        handleFileListChunk(value)
    }

    private suspend fun readRssi() {
        ble.readRssi()
    }

    private suspend fun sendTimeSync() {
        val now = System.currentTimeMillis()
        val offsetMinutes = TimeZone.getDefault().getOffset(now) / 60_000
        if (sendCommand(Cmd.timeSync(now / 1000, offsetMinutes))) {
            AppLog.i(TAG, "校时 tz=${offsetMinutes}min")
        }
    }

    private suspend fun readIdentity() {
        val wifiMac = readString(BleUuids.CHAR_HW_MAC)
        val deviceId = readString(BleUuids.CHAR_DEVICE_ID)
        val deviceKey = readString(BleUuids.CHAR_DEVICE_KEY)
        val serial = readString(BleUuids.CHAR_SERIAL)
        // 0x2A28 Software Revision = 应用版本（固件取的是 esp_app_desc 的 project version）；
        // 0x2A26 Firmware Revision 上报的是 IDF 版本（形如 v5.5.5），拿它做版本门控是错的。
        val firmware = readString(BleUuids.CHAR_SOFTWARE)
        val hardware = readString(BleUuids.CHAR_HARDWARE)
        val model = readString(BleUuids.CHAR_MODEL)
        val manufacturer = readString(BleUuids.CHAR_MANUFACTURER)
        val identity = Identity(
            deviceId = deviceId,
            deviceKey = deviceKey,
            wifiMac = wifiMac,
            serial = serial,
            firmware = firmware,
            hardware = hardware,
            manufacturer = manufacturer,
            model = model,
        )
        updateSession { it.copy(identity = identity) }
        val address = _session.value?.address ?: return
        updateDevice(address) { device ->
            device.mergedIdentity(
                deviceId = identity.deviceId,
                serial = identity.serial,
                firmware = identity.firmware,
                hardware = identity.hardware,
                wifiMac = identity.wifiMac,
            )
        }
        AppLog.i(
            "Identity",
            "id=${identity.deviceId} fw=${identity.firmware} hw=${identity.hardware} model=${identity.model}",
        )
    }

    private suspend fun readString(uuid: UUID): String? {
        val value = ble.read(uuid) ?: return null
        return String(value, StandardCharsets.UTF_8).trim().ifBlank { null }
    }

    /**
     * 传输进行中。
     *
     * 传输期间**不要**发状态/RSSI/文件列表这类小操作：Android 一次只允许一个
     * outstanding GATT 操作，而 [BleConnection] 用一把锁把写和读串起来，
     * 一次 read 最长能占住 4 秒（[BleConnection.read] 的超时），
     * 期间流控 ACK 全排在它后面 → 设备干等信用 → `Credit timeout`。
     */
    private fun transferBusy(): Boolean =
        receiver.active || _session.value?.transfer?.mode == TransferMode.BLE

    fun refreshStatus() {
        if (_session.value?.demo == true) return
        if (transferBusy()) return
        scope.launch {
            if (!ble.isReady) return@launch
            readStatus()
            readRssi()
        }
    }

    fun requestAllFiles() {
        if (_session.value?.demo == true) return
        scope.launch {
            if (!ble.isReady) return@launch
            expectFullList = true
            fileListAssembler.reset()
            sendCommand(Cmd.single(Cmd.GET_ALL_FILES))
            scheduleListReset()
        }
    }

    fun refreshFiles() {
        if (_session.value?.demo == true) return
        if (transferBusy()) return
        scope.launch {
            if (!ble.isReady) return@launch
            readStatus()
            readFileList()
            requestAllFiles()
            sendCommand(Cmd.single(Cmd.GET_STORAGE))
        }
    }

    fun startSync(auto: Boolean = false) {
        val s = _session.value ?: return
        if (!s.isReady) {
            showMessage("设备未连接")
            return
        }
        if (apJob?.isActive == true || s.status?.wifiAp == true) {
            showMessage("请先结束高速传输")
            return
        }
        if (receiver.active || s.status?.transfer?.active == true) {
            showMessage("已有传输正在进行")
            return
        }
        if (auto) {
            autoSyncInFlight = true
        }
        val device = deviceOf(s.address)
        if (!auto && device?.preferHighSpeed == true) {
            startHighSpeed()
            return
        }
        val pendingCount = s.status?.pendingFiles ?: s.pendingFiles.size
        if (pendingCount <= 0) {
            showMessage("没有待上传文件")
            autoSyncInFlight = false
            return
        }
        scope.launch {
            receiver.reset()
            autoSyncArmed = false
            autoSyncInFlight = true
            if (!sendCommand(Cmd.single(Cmd.START_SYNC))) {
                autoSyncInFlight = false
                showMessage("同步命令发送失败")
                return@launch
            }
            updateSession {
                it.copy(
                    transfer = TransferView(
                        mode = TransferMode.BLE,
                        fileCount = pendingCount,
                        total = s.status?.pendingBytes ?: s.pendingBytes,
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            }
            showMessage("开始同步 $pendingCount 个文件")
            TransferNotifications.show(app, "录音同步", "正在接收待上传文件…")
        }
    }

    fun stopTransfer() {
        scope.launch {
            receiver.abort(deletePartial = true)
            sendCommand(Cmd.single(Cmd.TRANSFER_STOP))
            sendCommand(Cmd.single(Cmd.STOP_SYNC))
            autoSyncInFlight = false
            autoSyncArmed = false
            updateSession { it.copy(transfer = null, pendingResume = null) }
            TransferNotifications.cancel(app)
            showMessage("传输已停止")
        }
    }

    fun resumeTransfer() {
        val partial = _session.value?.pendingResume ?: return
        scope.launch {
            if (!ble.isReady) {
                showMessage("设备未连接")
                return@launch
            }
            receiver.reset()
            if (sendCommand(Cmd.withText(Cmd.TRANSFER_RESUME, partial.fileId))) {
                showMessage("请求续传 ${FileIds.display(partial.fileId)}")
                delay(6_000)
                val current = _session.value
                if (current?.pendingResume?.fileId == partial.fileId &&
                    !receiver.active &&
                    current.transfer == null
                ) {
                    updateSession { it.copy(pendingResume = null) }
                    showMessage("续传未开始，设备挂起会话可能已过期")
                }
            }
        }
    }

    /**
     * 设备给的续传基点比本地分片还长 —— 本地缺了一段不可恢复的数据。
     *
     * 接收侧已经把分片删掉并停止接收（绝不用 0 补齐，否则长度校验会通过、
     * 坏文件会被当成正常录音保存）。这里负责收尾：让设备退出这次续传
     * （设备侧 Abort() 会丢弃挂起会话，坏断点不会留在卡上），然后重新发起一次
     * **完整**传输 —— 新一轮从 offset=0 开始，不会再次触发同样的不一致。
     */
    private fun handleResumeMismatch(fileId: String, localBytes: Long, deviceOffset: Long) {
        if (resumeRecoveryJob?.isActive == true) return
        resumeRecoveryJob = scope.launch {
            AppLog.w(
                TAG,
                "续传基点不一致 $fileId：本地 ${localBytes}B < 设备 ${deviceOffset} B — 改为整文件重传",
            )
            showMessage("续传基点不一致（本地 ${localBytes}B < 设备 ${deviceOffset}B），已丢弃分片并重传")
            updateSession { it.copy(pendingResume = null, transfer = null) }
            sendCommand(Cmd.single(Cmd.TRANSFER_STOP))
            sendCommand(Cmd.single(Cmd.STOP_SYNC))
            // 等设备把 abort 处理完并推一帧新状态，否则 startSync 会以为"已有传输正在进行"
            delay(800)
            readStatus()
            delay(150)
            if (_session.value?.isReady == true && _session.value?.demo != true && !receiver.active) {
                startSync()
            }
        }
    }

    fun dismissResume() {
        updateSession { it.copy(pendingResume = null) }
    }

    fun notify(message: String) {
        showMessage(message)
    }

    fun clearPartials() {
        scope.launch(Dispatchers.IO) {
            val files = library.partialDir.listFiles()
            var removed = 0
            files?.forEach { file ->
                if (file.isFile && file.delete()) removed++
            }
            withContext(Dispatchers.Main) {
                // 分片删了就必须同时清掉"继续传输"入口：否则用户点继续时，
                // 设备按它自己的断点发 FILE_START(offset>0)，本地却是 0 字节，
                // 正好落进"续传基点不一致"那条路径（早先那版会补零，更糟）。
                updateSession { it.copy(pendingResume = null) }
                showMessage(if (removed > 0) "已清除 $removed 个未完成分片" else "没有未完成分片")
            }
        }
    }

    fun deleteRemoteFile(id: String) {
        scope.launch {
            if (sendCommand(Cmd.withText(Cmd.DELETE_FILE, id))) {
                showMessage("正在删除…")
                delay(700)
                requestAllFiles()
            }
        }
    }

    fun markPending(id: String) {
        scope.launch {
            if (sendCommand(Cmd.withText(Cmd.MARK_PENDING, id))) {
                delay(500)
                requestAllFiles()
            }
        }
    }

    fun markUploaded(id: String) {
        scope.launch {
            if (sendCommand(Cmd.withText(Cmd.FILE_DONE, id))) {
                delay(500)
                requestAllFiles()
            }
        }
    }

    fun formatDevice() {
        scope.launch {
            if (sendCommand(Cmd.single(Cmd.FORMAT))) {
                showMessage("已发送格式化请求")
                delay(1_500)
                requestAllFiles()
                readStatus()
            }
        }
    }

    // -------------------------------------------------------------- high speed

    fun startHighSpeed() {
        val s = _session.value ?: return
        if (s.demo) {
            showMessage("演示模式不支持高速传输")
            return
        }
        if (!s.isReady) {
            showMessage("设备未连接")
            return
        }
        if (apJob?.isActive == true || apStopping) {
            showMessage("高速传输正在进行")
            return
        }
        if (s.status?.recording == true) {
            showMessage("录音中无法开启高速传输，请先结束录音")
            return
        }
        if (s.status?.storage == null) {
            showMessage("设备报告存储未挂载，高速传输可能失败，请检查 SD 卡")
        }
        apJob = scope.launch { runHighSpeed() }
    }

    fun switchToHighSpeed() {
        showMessage("正在切换到高速传输…")
        startHighSpeed()
    }

    fun stopHighSpeed() {
        if (apJob == null) {
            manualWifiDeferred?.complete(null)
            manualWifiDeferred = null
            wifi.release()
            unbindProcessNetwork()
            _ap.value = ApState()
            TransferNotifications.cancel(app)
            return
        }
        apStopping = true
        apJob?.cancel()
        showMessage("正在结束高速传输…")
    }

    fun dismissApResult() {
        if (_ap.value.phase == ApPhase.ERROR || _ap.value.phase == ApPhase.DONE) {
            _ap.value = ApState()
        }
    }

    private suspend fun runHighSpeed() {
        val session = _session.value ?: return
        val address = session.address
        val deviceName = session.name
        var apEnabled = false
        var completed = 0
        apFailure = null
        unbindProcessNetwork()
        try {
            if (_session.value?.status?.wifiAp == true || _session.value?.apInfo?.enabled == true) {
                AppLog.i(TAG, "清理上一次热点会话后再开启")
                sendCommand(Cmd.single(Cmd.DISABLE_AP))
                val deadline = SystemClock.elapsedRealtime() + 6_000
                while (SystemClock.elapsedRealtime() < deadline &&
                    _session.value?.status?.wifiAp != false
                ) {
                    delay(250)
                }
                delay(300)
            }
            _ap.value = ApState(phase = ApPhase.ENABLING, startedAt = System.currentTimeMillis())
            if (_session.value?.address == address) {
                // A new AP session uses a new random SSID; drop the previous one so
                // awaitApInfo() cannot return a stale SSID from an older session.
                updateSession { it.copy(apInfo = ApInfo()) }
            }
            if (receiver.active || _session.value?.status?.transfer?.active == true) {
                AppLog.i(TAG, "切换到高速传输：先停止 BLE 传输")
                sendCommand(Cmd.single(Cmd.TRANSFER_STOP))
                sendCommand(Cmd.single(Cmd.STOP_SYNC))
                receiver.abort(deletePartial = true)
                updateSession { it.copy(transfer = null) }
                delay(600)
            }
            if (!sendCommand(Cmd.single(Cmd.ENABLE_AP))) {
                throw IOException("开启热点命令发送失败")
            }
            val info = awaitApInfo(15_000)
            apEnabled = true
            _ap.update {
                it.copy(phase = ApPhase.WAITING_WIFI, ssid = info.ssid, ip = info.ip ?: DEFAULT_AP_HOST)
            }
            delay(600)
            val ssid = info.ssid!!
            val fallbackHost = info.ip ?: DEFAULT_AP_HOST
            TransferNotifications.show(app, "高速传输", "正在连接设备热点 $ssid…")
            val connectResult = wifi.connect(ssid, timeoutMs = 15_000)
            var manualFallbackUsed = connectResult !is WifiConnectResult.Connected
            var network: Network = when (connectResult) {
                is WifiConnectResult.Connected -> connectResult.network
                else -> {
                    val reason = when (connectResult) {
                        WifiConnectResult.PermissionMissing ->
                            "缺少 WiFi 权限，请在系统设置中授予附近设备/位置权限"

                        WifiConnectResult.Rejected ->
                            "系统拒绝了热点连接请求（部分机型被通知或后台弹窗权限拦截）"

                        WifiConnectResult.Timeout -> "等待系统确认超时"
                        is WifiConnectResult.Failed -> connectResult.message
                        is WifiConnectResult.Connected -> ""
                    }
                    AppLog.w("HS", "自动连接热点失败：$reason，转手动连接")
                    val manualDeferred = CompletableDeferred<Network?>()
                    manualWifiDeferred = manualDeferred
                    _ap.update {
                        it.copy(
                            phase = ApPhase.WAITING_MANUAL,
                            ssid = ssid,
                            ip = fallbackHost,
                            message = reason,
                        )
                    }
                    TransferNotifications.show(app, "高速传输", "请手动连接热点 $ssid")
                    awaitManualNetwork(manualDeferred)
                        ?: throw IOException("手动连接超时，请重试")
                }
            }
            var http: DeviceHttpClient? = null
            var files: List<RemoteFile>? = null
            while (files == null) {
                val host = resolveHost(network, fallbackHost)
                val client = DeviceHttpClient(network, host)
                _ap.update { it.copy(phase = ApPhase.LISTING, ip = host) }
                TransferNotifications.show(app, "高速传输", "正在读取文件列表…")
                try {
                    files = fetchFilesWithRetry(
                        http = client,
                        host = host,
                        attemptsPerMode = if (manualFallbackUsed) 2 else 1,
                    )
                    http = client
                } catch (e: Exception) {
                    if (manualFallbackUsed) throw e
                    // Auto-connect succeeded but its network cannot reach the card
                    // (common on Xiaomi dual-WLAN). Keep the AP alive and let the
                    // user join the hotspot from system WLAN, like the browser test.
                    manualFallbackUsed = true
                    AppLog.w("HS", "自动连接后 HTTP 不可达，转手动连接: ${e.message}")
                    wifi.release()
                    unbindProcessNetwork()
                    val manualDeferred = CompletableDeferred<Network?>()
                    manualWifiDeferred = manualDeferred
                    _ap.update {
                        it.copy(
                            phase = ApPhase.WAITING_MANUAL,
                            ssid = ssid,
                            ip = host,
                            message = "自动连接热点后无法访问设备 HTTP。" +
                                "请在系统 WLAN 中手动连接该热点，返回后点「我已连接」。",
                        )
                    }
                    TransferNotifications.show(app, "高速传输", "请手动连接热点 $ssid")
                    network = awaitManualNetwork(manualDeferred)
                        ?: throw IOException("手动连接超时，请重试")
                }
            }
            val httpClient = http ?: throw IOException("HTTP 客户端未初始化")
            val fileList = files ?: emptyList()
            if (fileList.isEmpty()) {
                _ap.update { it.copy(phase = ApPhase.DONE, message = "没有待上传文件") }
                showMessage("设备上没有待上传文件")
                return
            }
            val totalBytes = fileList.sumOf { it.size }
            _ap.update {
                it.copy(
                    phase = ApPhase.DOWNLOADING,
                    fileCount = fileList.size,
                    completed = 0,
                    bytesTotal = totalBytes,
                    startedAt = System.currentTimeMillis(),
                )
            }
            fileList.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val saveId = FileIds.normalize(file.id)
                val part = library.partialFile(saveId, "wifi")
                part.delete()
                var lastTick = SystemClock.elapsedRealtime()
                var lastBytes = 0L
                var speed = 0L
                var lastNotify = 0L
                _ap.update {
                    it.copy(
                        fileId = file.id,
                        fileIndex = index + 1,
                        bytesDone = 0,
                        bytesTotal = file.size.coerceAtLeast(1),
                        speedBps = 0,
                    )
                }
                val ext = withContext(Dispatchers.IO) {
                    httpClient.download(file.id, part) { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTick >= 400) {
                            speed = (done - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)
                            lastTick = now
                            lastBytes = done
                            _ap.update {
                                it.copy(
                                    bytesDone = done,
                                    bytesTotal = total.takeIf { t -> t > 0 } ?: file.size,
                                    speedBps = speed,
                                )
                            }
                            if (now - lastNotify >= 1_500) {
                                lastNotify = now
                                TransferNotifications.show(
                                    app,
                                    "高速传输",
                                    "正在下载 ${FileIds.display(file.id)}",
                                    if (total > 0) (done * 100 / total).toInt() else null,
                                )
                            }
                        }
                    }
                }
                val recording = withContext(Dispatchers.IO) {
                    library.commit(part, saveId, ext, address, deviceName)
                }
                if (recording == null) {
                    part.delete()
                    throw IOException("保存 ${file.id} 失败")
                }
                withContext(Dispatchers.IO) { httpClient.markUploaded(file.id) }
                completed++
                _ap.update { it.copy(completed = completed, speedBps = speed) }
                refreshLibrary()
                showMessage("已下载 ${FileIds.display(file.id)}")
            }
            _ap.update {
                it.copy(
                    phase = ApPhase.DONE,
                    message = "高速传输完成，共 $completed 个文件",
                    bytesDone = totalBytes,
                )
            }
            showMessage("高速传输完成，共 $completed 个文件")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("HS", "高速传输失败: ${e.message}")
            _ap.update { it.copy(phase = ApPhase.ERROR, message = e.message ?: "高速传输失败") }
            showMessage("高速传输失败：${e.message}")
        } finally {
            withContext(NonCancellable) {
                manualWifiDeferred?.complete(null)
                manualWifiDeferred = null
                if (apEnabled && _session.value?.address == address) {
                    runCatching { sendCommand(Cmd.single(Cmd.DISABLE_AP)) }
                }
                wifi.release()
                TransferNotifications.cancel(app)
                unbindProcessNetwork()
                apFailure = null
                apStopping = false
                if (_session.value?.address == address) {
                    updateSession { it.copy(apInfo = ApInfo()) }
                }
                if (_ap.value.phase != ApPhase.ERROR) _ap.value = ApState()
            }
            scope.launch {
                delay(900)
                requestAllFiles()
                refreshStatus()
            }
        }
    }

    private suspend fun awaitApInfo(timeoutMs: Long): ApInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            apFailure?.let { throw IOException(it) }
            val info = _session.value?.apInfo
            if (info?.enabled == true && !info.ssid.isNullOrBlank()) return info
            if (!sendCommand(Cmd.single(Cmd.GET_WIFI_STATUS))) break
            delay(1_200)
        }
        throw IOException(
            if (_session.value?.status?.storage == null) {
                "设备存储未挂载（SD 卡），无法开启热点。请检查录音卡存储后重新上电。"
            } else {
                "等待设备热点超时"
            },
        )
    }

    private suspend fun awaitManualNetwork(
        deferred: CompletableDeferred<Network?>,
    ): Network? {
        return try {
            withTimeoutOrNull(60_000) { deferred.await() }
        } finally {
            if (manualWifiDeferred === deferred) manualWifiDeferred = null
        }
    }

    /**
     * Called when the user has joined the card hotspot from system Wi-Fi
     * settings. Probes every Wi-Fi network for the device HTTP endpoint.
     */
    fun confirmManualWifi() {
        val ssid = _ap.value.ssid
        scope.launch {
            showMessage("正在检测设备热点…")
            unbindProcessNetwork()
            val network = findDeviceWifiNetwork()
            if (network == null) {
                updateSession {
                    it.copy(
                        apInfo = it.apInfo.copy(
                            ip = it.apInfo.ip ?: DEFAULT_AP_HOST,
                        ),
                    )
                }
                _ap.update {
                    it.copy(
                        message = "未检测到设备 HTTP。请确认已连接热点；" +
                            "可用浏览器打开 http://192.168.4.1/api/files 验证。",
                    )
                }
                showMessage("未检测到设备热点，请确认已连接 ${ssid ?: "RecordingCard-XXXX"}")
            } else {
                manualWifiDeferred?.complete(network)
            }
        }
    }

    private suspend fun findDeviceWifiNetwork(): Network? = withContext(Dispatchers.IO) {
        val host = _ap.value.ip ?: DEFAULT_AP_HOST
        val manager = app.getSystemService(ConnectivityManager::class.java)
        // A previous bindProcessToNetwork() may point at a released network and
        // make every new socket fail with ENONET. Always reset before probing.
        unbindProcessNetwork()
        if (probeDefaultHttp(host)) {
            // Browser-style default route already reaches the card; any Wi-Fi
            // network is enough for the HTTP client's default mode.
            val wifiNetworks = manager?.allNetworks?.filter { network ->
                manager?.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.orEmpty()
            if (wifiNetworks.isNotEmpty()) return@withContext wifiNetworks.first()
        }
        if (manager != null) {
            val wifiNetworks = manager.allNetworks.filter { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            for (network in wifiNetworks) {
                // Bind the process default only for the probe; the HTTP client
                // uses network.openConnection() and the process stays unbound.
                runCatching { manager.bindProcessToNetwork(network) }
                val ok = probeDefaultHttp(host) || probeDeviceHttp(network, host)
                runCatching { manager.bindProcessToNetwork(null) }
                if (ok) return@withContext network
            }
            runCatching { manager.bindProcessToNetwork(null) }
        }
        null
    }

    /** Clears a stale process-wide network binding (otherwise sockets fail ENONET). */
    private fun unbindProcessNetwork() {
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }
    }

    private fun probeDefaultHttp(host: String): Boolean = runCatching {
        val connection = URL("http://$host/api/files").openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun probeDeviceHttp(network: Network, host: String): Boolean = runCatching {
        val connection = network.openConnection(URL("http://$host/api/files")) as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /** The SoftAP gateway is the card; prefer it over the cached transient IP. */
    private fun resolveHost(network: Network, fallback: String): String = runCatching {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val gateway = manager?.getLinkProperties(network)
            ?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
        if (!gateway.isNullOrBlank()) gateway else fallback
    }.getOrDefault(fallback)

    private suspend fun fetchFilesWithRetry(
        http: DeviceHttpClient,
        host: String,
        attemptsPerMode: Int = 2,
    ): List<RemoteFile> {
        var lastError: Exception? = null
        // Default network first: on Xiaomi/HyperOS dual-WLAN the socket bound to
        // the WifiNetworkSpecifier Network can time out while the browser (default
        // route) reaches 192.168.4.1 fine.
        val modes = if (http.hasBoundNetwork) listOf(false, true) else listOf(false)
        for (boundMode in modes) {
            http.setBoundMode(boundMode)
            repeat(attemptsPerMode) { attempt ->
                try {
                    val files = withContext(Dispatchers.IO) { http.listFiles() }
                    AppLog.i("HS", "文件列表成功（${http.modeLabel()}）")
                    return files
                } catch (e: Exception) {
                    lastError = e
                    AppLog.w(
                        "HS",
                        "文件列表失败（${http.modeLabel()} ${attempt + 1}/2）: ${e.message}",
                    )
                    if (attempt < attemptsPerMode - 1) delay(1_200)
                }
            }
        }
        val storageHint = if (_session.value?.status?.storage == null) {
            "设备当前报告存储未挂载（SD 卡）。请检查录音卡存储并重新上电后重试。"
        } else {
            "请重新上电录音卡后重试。"
        }
        throw IOException(
            "已连上热点，但设备 HTTP 服务（$host:80）无响应" +
                "（${lastError?.message ?: "未知错误"}）。$storageHint",
            lastError,
        )
    }

    private fun cancelApSession() {
        if (apJob != null) {
            apStopping = true
            apJob?.cancel()
        } else {
            manualWifiDeferred?.complete(null)
        }
        manualWifiDeferred = null
        wifi.release()
        unbindProcessNetwork()
        TransferNotifications.cancel(app)
        _ap.value = ApState()
        updateSession { it.copy(apInfo = ApInfo()) }
    }

    // -------------------------------------------------------------------- scan

    fun startScan() {
        if (_scan.value.running) return
        synchronized(scanDevices) { scanDevices.clear() }
        _scan.value = ScanState(running = true)
        if (!scanner.start()) {
            _scan.update { it.copy(running = false) }
            return
        }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        scanner.stop()
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        _scan.update { it.copy(running = false) }
    }

    private fun onScanDevice(device: ScanDevice) {
        synchronized(scanDevices) { scanDevices[device.address] = device }
        val list = synchronized(scanDevices) { scanDevices.values.toList() }
        _scan.update {
            it.copy(devices = list.sortedByDescending { d -> d.rssi }, running = true, error = null)
        }
    }

    // ---------------------------------------------------------------- settings

    fun setAutoSync(address: String, enabled: Boolean) {
        updateDevice(address) { it.copy(autoSync = enabled) }
        if (enabled) {
            autoSyncArmed = true
            maybeAutoSync()
        }
    }

    fun setPreferHighSpeed(address: String, enabled: Boolean) {
        updateDevice(address) { it.copy(preferHighSpeed = enabled) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        store.saveSettings(next)
    }

    // ---------------------------------------------------------------- library

    fun refreshLibrary() {
        scope.launch(Dispatchers.IO) {
            val list = library.list()
            _recordings.value = list
        }
    }

    fun playRecording(recording: LocalRecording) = player.play(recording)
    fun togglePlayback() = player.toggle()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun stopPlayback() = player.stop()

    fun deleteRecording(recording: LocalRecording) {
        if (playerState.value.recordingId == recording.id) player.stop()
        if (library.delete(recording)) {
            refreshLibrary()
            showMessage("已删除 ${FileIds.display(recording.id)}")
        } else {
            showMessage("删除失败")
        }
    }

    fun clearLogs() = AppLog.clear()

    fun shutdown() {
        scanner.stop()
        ble.close()
        player.release()
        scope.cancel()
    }

    // ----------------------------------------------------------------- helpers

    private fun maybeAutoSync() {
        val s = _session.value ?: return
        if (!s.isReady) return
        val device = deviceOf(s.address) ?: return
        if (!device.autoSync) return
        val status = s.status ?: return
        if (status.pendingFiles <= 0) {
            autoSyncArmed = true
            autoSyncInFlight = false
            return
        }
        if (!autoSyncArmed || autoSyncInFlight) return
        if (status.wifiAp || status.ota != "idle") return
        if (status.transfer.active || receiver.active) return
        if (apJob?.isActive == true) return
        autoSyncArmed = false
        autoSyncInFlight = true
        AppLog.i(TAG, "待上传 ${status.pendingFiles} 个文件，自动开始同步")
        startSync(auto = true)
    }

    private fun cacheDeviceStatus(status: DeviceStatus) {
        val address = _session.value?.address ?: return
        val device = deviceOf(address) ?: return
        val battery = status.battery?.socPct
        val batteryChanged = battery != null && battery != device.lastBatteryPct
        val pendingChanged = status.pendingFiles != device.lastPendingFiles
        if (batteryChanged || pendingChanged) {
            updateDevice(address) {
                it.copy(
                    lastBatteryPct = battery ?: it.lastBatteryPct,
                    lastPendingFiles = status.pendingFiles,
                )
            }
        }
    }

    private fun deviceOf(address: String): SavedDevice? =
        _devices.value.firstOrNull { it.address == address }

    private fun updateDevice(address: String, transform: (SavedDevice) -> SavedDevice) {
        val list = _devices.value.toMutableList()
        val index = list.indexOfFirst { it.address == address }
        if (index < 0) return
        val updated = transform(list[index])
        if (updated == list[index]) return
        list[index] = updated
        _devices.value = list
        store.saveDevices(list)
    }

    private fun updateSession(transform: (SessionState) -> SessionState) {
        val current = _session.value ?: return
        _session.value = transform(current).copy(updatedAt = System.currentTimeMillis())
    }

    private fun showMessage(message: String) {
        if (message.isBlank()) return
        _messages.tryEmit(message)
    }

    private fun hex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    // ------------------------------------------------------------------ demo

    private fun isDemoAddress(address: String): Boolean = address.startsWith("DEMO-")

    /** Adds a local demo card so the UI can be previewed without hardware. */
    fun addDemoDevice() {
        val existing = _devices.value.firstOrNull { isDemoAddress(it.address) }
        if (existing != null) {
            selectDevice(existing.address)
            showMessage("已打开演示设备")
            return
        }
        val device = SavedDevice(
            address = "DEMO-0001",
            name = "RecordingCard-DEMO",
            deviceId = "RC1-202610-00001-DEMO",
            serial = "DEMO0000CARD01",
            firmware = "1.4.10",
            hardware = "ESP32-S3",
            wifiMac = "DC:DA:0C:00:00:01",
            addedAt = System.currentTimeMillis(),
            lastConnectedAt = System.currentTimeMillis(),
            lastBatteryPct = 78,
            lastPendingFiles = 3,
            autoSync = false,
            preferHighSpeed = false,
        )
        _devices.update { it + device }
        store.saveDevices(_devices.value)
        selectDevice(device.address)
        showMessage("已添加演示设备，仅用于预览界面")
    }

    private fun demoSession(device: SavedDevice): SessionState {
        val pending = listOf(
            RemoteFile("20261023_091500", 1_248_000, false),
            RemoteFile("20261023_074500", 642_560, false),
            RemoteFile("20261022_183000", 2_457_600, false),
        )
        val uploaded = listOf(
            RemoteFile("20261022_180000", 3_200_000, true),
            RemoteFile("20261022_161500", 880_000, true),
        )
        val status = DeviceStatus(
            recording = false,
            recordingPaused = false,
            recordingSeconds = 0,
            recMode = 1,
            recChannels = 2,
            recSampleRate = 16_000,
            bleConnected = true,
            wifiAp = false,
            ota = "idle",
            battery = BatteryInfo(4050, 78, 27, charging = false, low = false),
            storage = StorageInfo(totalKb = 15_300_000, freeKb = 9_800_000),
            pendingFiles = pending.size,
            pendingBytes = pending.sumOf { it.size },
            transfer = TransferInfo(),
        )
        return SessionState(
            address = device.address,
            name = device.displayName,
            phase = ConnPhase.READY,
            mtu = 512,
            status = status,
            identity = Identity(
                deviceId = device.deviceId,
                serial = device.serial,
                firmware = device.firmware,
                hardware = device.hardware,
                wifiMac = device.wifiMac,
                model = "RecordingCard",
                manufacturer = "AZB",
            ),
            pendingFiles = pending,
            allFiles = pending + uploaded,
            allFilesLoaded = true,
            rssi = -61,
            demo = true,
            updatedAt = System.currentTimeMillis(),
        )
    }
    companion object {
        private const val TAG = "App"
        private const val MAX_RECONNECT = 2
        private const val SCAN_DURATION_MS = 30_000L
        private const val PARTIAL_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val DEFAULT_AP_HOST = "192.168.4.1"
    }
}







