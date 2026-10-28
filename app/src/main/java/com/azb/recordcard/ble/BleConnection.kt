package com.azb.recordcard.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.azb.recordcard.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

enum class BleConnectionState { DISCONNECTED, CONNECTING, DISCOVERING, READY, FAILED }

sealed interface BleEvent {
    data class State(
        val state: BleConnectionState,
        val message: String? = null,
        val address: String? = null,
    ) : BleEvent

    data class Ready(val mtu: Int, val address: String? = null) : BleEvent
    data class Notification(val characteristic: UUID, val value: ByteArray) : BleEvent
    data class Rssi(val rssi: Int) : BleEvent
}

/**
 * GATT 客户端门面。
 *
 * 传输层由 Nordic Android-BLE-Library（[BleManager]）承担：连接与 133 重试、服务发现、
 * "同一时刻只跑一个 GATT 操作"的请求队列、MTU 协商、CCCD 订阅、请求级超时。
 * 本类只做两件事：把库的事件翻译成 [BleEvent]，把库的 `protected` 请求封装成与旧实现
 * 一致的 `suspend` + `Boolean` 形态 —— 这样协议层与业务层完全不需要知道库的存在。
 *
 * 通知回调单独跑一个 HandlerThread：[BleUuids.CHAR_FILE_DATA] 每秒几十到上百帧，
 * 库默认走主线程，会把收帧延迟绑到 UI 卡顿上。
 */
class BleConnection(
    context: Context,
    private val scope: CoroutineScope,
    private val onEvent: (BleEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val systemManager = appContext.getSystemService(BluetoothManager::class.java)

    private val callbackThread = HandlerThread("ble-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private var manager = CardBleManager()
    private var stateJob: Job? = null

    @Volatile
    var mtu: Int = 23
        private set

    @Volatile
    var state: BleConnectionState = BleConnectionState.DISCONNECTED
        private set

    @Volatile
    var connectedAddress: String? = null
        private set

    val isReady: Boolean get() = state == BleConnectionState.READY && manager.isConnected

    init {
        observeManager()
    }

    fun connect(address: String) {
        if (!hasConnectPermission()) {
            fail("缺少蓝牙连接权限")
            return
        }
        val adapter = systemManager?.adapter
        if (adapter == null) {
            fail("此设备不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            fail("蓝牙未开启，请先打开蓝牙")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            fail("无效的设备地址")
            return
        }
        // 每次连接都用全新的 manager：库不保证同一实例在 close() 之后还能可靠复用，
        // 而"换设备连接"这条路径必须把上一台的 GATT 彻底释放掉。
        resetManager()
        connectedAddress = address
        setState(BleConnectionState.CONNECTING, "正在连接…")
        AppLog.i(TAG, "connect $address")
        try {
            manager.connect(device)
                .retry(3, 100)
                .timeout(CONNECT_TIMEOUT_MS)
                .enqueue()
        } catch (e: SecurityException) {
            fail("缺少蓝牙连接权限")
        } catch (e: Exception) {
            AppLog.e(TAG, "connect 异常: ${e.message}")
            fail("无法建立 GATT 连接")
        }
    }

    fun disconnect() {
        try {
            manager.disconnect().enqueue()
        } catch (e: Exception) {
            AppLog.w(TAG, "disconnect 异常: ${e.message}")
        }
        connectedAddress = null
        mtu = 23
        state = BleConnectionState.DISCONNECTED
        onEvent(BleEvent.State(BleConnectionState.DISCONNECTED, "已断开连接", null))
    }

    fun close() {
        connectedAddress = null
        state = BleConnectionState.DISCONNECTED
        mtu = 23
        resetManager(recreate = false)
        callbackThread.quitSafely()
    }

    suspend fun write(uuid: UUID, data: ByteArray, withResponse: Boolean = true): Boolean =
        manager.write(uuid, data, withResponse)

    suspend fun read(uuid: UUID): ByteArray? = manager.read(uuid)

    suspend fun readRssi(): Int? {
        val rssi = manager.readRssiValue() ?: return null
        onEvent(BleEvent.Rssi(rssi))
        return rssi
    }

    /** 丢弃当前 manager（连同它的 GATT 与状态流），按需换一个新实例。 */
    private fun resetManager(recreate: Boolean = true) {
        stateJob?.cancel()
        stateJob = null
        try {
            manager.close()
        } catch (e: Exception) {
            AppLog.w(TAG, "close 异常: ${e.message}")
        }
        mtu = 23
        if (recreate) {
            manager = CardBleManager()
            observeManager()
        }
    }

    private fun observeManager() {
        val current = manager
        stateJob = scope.launch {
            current.stateAsFlow().collect { onConnectionState(it) }
        }
    }

    private fun setState(
        newState: BleConnectionState,
        message: String? = null,
        address: String? = connectedAddress,
    ) {
        state = newState
        onEvent(BleEvent.State(newState, message, address))
    }

    private fun fail(message: String) {
        AppLog.e(TAG, message)
        connectedAddress = null
        resetManager(recreate = false)
        setState(BleConnectionState.FAILED, message, null)
    }

    private fun onConnectionState(connectionState: ConnectionState) {
        when (connectionState) {
            is ConnectionState.Connecting ->
                setState(BleConnectionState.CONNECTING, "正在连接…")

            is ConnectionState.Initializing ->
                setState(BleConnectionState.DISCOVERING, "正在发现服务…")

            is ConnectionState.Ready -> {
                mtu = manager.mtuNow
                state = BleConnectionState.READY
                AppLog.i(TAG, "MTU = $mtu")
                onEvent(BleEvent.State(BleConnectionState.READY, "已连接", connectedAddress))
                onEvent(BleEvent.Ready(mtu, connectedAddress))
            }

            is ConnectionState.Disconnecting -> Unit

            is ConnectionState.Disconnected -> onDisconnected(connectionState)
        }
    }

    private fun onDisconnected(connectionState: ConnectionState.Disconnected) {
        // disconnect()/fail() 已经同步报过断连（地址已清空），这里丢掉重复事件，
        // 否则上层会对同一次断开处理两遍。
        if (connectedAddress == null &&
            (state == BleConnectionState.DISCONNECTED || state == BleConnectionState.FAILED)
        ) {
            return
        }
        val reason = connectionState.reason
        val message = when (reason) {
            ConnectionState.Disconnected.Reason.TERMINATE_PEER_USER -> "对端主动断开"
            ConnectionState.Disconnected.Reason.LINK_LOSS -> "连接已断开"
            ConnectionState.Disconnected.Reason.TIMEOUT -> "连接超时"
            ConnectionState.Disconnected.Reason.CANCELLED -> "连接已取消"
            ConnectionState.Disconnected.Reason.NOT_SUPPORTED -> "设备不支持所需服务"
            ConnectionState.Disconnected.Reason.TERMINATE_LOCAL_HOST -> "已断开连接"
            ConnectionState.Disconnected.Reason.SUCCESS -> "已断开连接"
            else -> "连接断开"
        }
        val previous = connectedAddress
        connectedAddress = null
        mtu = 23
        // 还没 READY 就断了 = 这次连接没建立成功，按 FAILED 上报（上层才会走
        // "连接失败 → 退避重连"；若报 DISCONNECTED，它只在"曾经 READY 过"时才重连，
        // 首连失败就再也不会自动重试）。READY 之后的断开（含链路丢失）都按断连处理。
        val target = if (reason == ConnectionState.Disconnected.Reason.NOT_SUPPORTED ||
            state != BleConnectionState.READY
        ) {
            BleConnectionState.FAILED
        } else {
            BleConnectionState.DISCONNECTED
        }
        AppLog.i(TAG, "断开: $reason ($message)")
        state = target
        onEvent(BleEvent.State(target, message, previous))
    }

    /**
     * 记录实际生效的连接参数，并按 Apple《Accessory Design Guidelines》体检。
     *
     * 为什么要记：Android 会拒绝或覆盖外设发起的连接参数更新（尤其"由慢变快"），
     * 而 App 层既看不到那个请求、也没有同意/拒绝的入口 —— 只有 host 回调的真实参数
     * 能说明"到底生效了没有、生效成多少"。
     *
     * 单位是 AOSP `onConnectionUpdated` 的口径：interval 1.25ms、timeout 10ms。
     * 只能看到生效后的单一 interval，看不到 min/max，所以 Apple 规则里
     * "Min + 15ms ≤ Max" 那条无法在这里校验。
     */
    private fun logConnectionParameters(intervalUnits: Int, latency: Int, timeoutUnits: Int) {
        val intervalMs = intervalUnits * 1.25
        val timeoutMs = timeoutUnits * 10
        AppLog.i(
            TAG,
            "连接参数 interval=%.2fms latency=%d timeout=%dms"
                .format(Locale.US, intervalMs, latency, timeoutMs),
        )
        val problems = buildList {
            if (intervalMs < 15.0) add("间隔 < 15ms")
            if (abs(intervalMs - round(intervalMs / 15.0) * 15.0) > 0.01) add("间隔不是 15ms 的整数倍")
            if (latency > 30) add("latency > 30")
            if (timeoutMs < 2_000) add("超时 < 2s")
            if (timeoutMs > 6_000) add("超时 > 6s")
            if (timeoutMs <= intervalMs * (latency + 1) * 3) add("超时没有大于 3×间隔×(latency+1)")
        }
        if (problems.isNotEmpty()) {
            AppLog.w(
                TAG,
                "连接参数不满足 Apple 规则（iOS 可能拒绝或忽略）: ${problems.joinToString("；")}",
            )
        }
    }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 把 [BleManager] 的 `protected` API 收拢成门面能用的 suspend 方法。
     *
     * 换库/回退只需要动这一个文件：上层看到的永远是 `write(): Boolean` / `read(): ByteArray?`。
     */
    private inner class CardBleManager : BleManager(appContext) {
        // 整表替换而不是原地增删：这张表在 GATT 回调线程写、在任意协程线程读，
        // 共享可变 Map 会在断连清表时和读并发。
        @Volatile
        private var chars: Map<UUID, BluetoothGattCharacteristic> = emptyMap()

        val mtuNow: Int get() = getMtu()

        /**
         * 把库日志并进 AppLog。
         *
         * 不要降低 `getMinLogPriority()`：VERBOSE/DEBUG 会逐帧打印数据内容，
         * 传输时每秒上百条，会把 AppLog 的 800 条环形缓冲刷成噪声。默认 INFO 已经
         * 包含连接/请求/错误这些我们真正需要的信息。
         */
        override fun log(priority: Int, message: String) {
            when (priority) {
                Log.ERROR, Log.ASSERT -> AppLog.e(TAG, message)
                Log.WARN -> AppLog.w(TAG, message)
                Log.DEBUG, Log.VERBOSE -> AppLog.d(TAG, message)
                else -> AppLog.i(TAG, message)
            }
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val found = LinkedHashMap<UUID, BluetoothGattCharacteristic>()
            gatt.services?.forEach { service ->
                service.characteristics?.forEach { found[it.uuid] = it }
            }
            chars = found
            return found.containsKey(BleUuids.CHAR_CONTROL)
        }

        override fun initialize() {
            // 只在这里挂：BleManager 的 handler 要等服务发现后才存在，早挂是空操作。
            setConnectionParametersListener { _, interval, latency, timeout ->
                logConnectionParameters(interval, latency, timeout)
            }
            // MTU/RSSI 请求是 SimpleRequest，库没给它们 timeout()；MTU 协商必然回调，
            // 不会卡住 initialize() → onDeviceReady 这条链。
            requestMtu(512).enqueue()
            for (uuid in BleUuids.NOTIFY_CHARACTERISTICS) {
                val characteristic = chars[uuid] ?: continue
                setNotificationCallback(characteristic)
                    .setHandler(callbackHandler)
                    .with { _, data ->
                        // 必须拷贝：库可能在回调返回后复用底层缓冲。
                        data.value?.let { value ->
                            onEvent(BleEvent.Notification(uuid, value.copyOf()))
                        }
                    }
                enableNotifications(characteristic).enqueue()
            }
        }

        override fun onServicesInvalidated() {
            chars = emptyMap()
        }

        suspend fun write(uuid: UUID, data: ByteArray, withResponse: Boolean): Boolean {
            val characteristic = chars[uuid] ?: return false
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val timeout = if (withResponse) WRITE_TIMEOUT_MS else WRITE_NO_RESPONSE_TIMEOUT_MS
            // 用 withTimeoutOrNull 而不是 Request.timeout()：库的 timeout() 在
            // SimpleRequest 上会退化成 TimeoutableRequest，拿不到具体返回类型；
            // 而 2.6+ 的 suspend 化请求支持取消，取消即从队列里摘掉。
            val sent = withTimeoutOrNull(timeout) {
                writeCharacteristic(characteristic, data, type).suspend()
                true
            }
            return sent == true
        }

        suspend fun read(uuid: UUID): ByteArray? {
            val characteristic = chars[uuid] ?: return null
            return withTimeoutOrNull(READ_TIMEOUT_MS) {
                readCharacteristic(characteristic).suspend().value
            }
        }

        suspend fun readRssiValue(): Int? = withTimeoutOrNull(READ_RSSI_TIMEOUT_MS) {
            readRssi().suspend()
        }
    }

    companion object {
        private const val TAG = "BLE"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val WRITE_NO_RESPONSE_TIMEOUT_MS = 1_000L
        private const val READ_TIMEOUT_MS = 4_000L
        private const val READ_RSSI_TIMEOUT_MS = 2_000L
    }
}
