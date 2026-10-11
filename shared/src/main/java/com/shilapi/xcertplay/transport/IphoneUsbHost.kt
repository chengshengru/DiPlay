package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.compat.UsbConfigurationView
import com.shilapi.xcertplay.compat.UsbDescriptorCompat
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                // 与上游 LIVI 一致（native/livi-helperd/crates/iap2-usbmux/src/linux.rs::request_carplay_configs）：
                // Apple 私有请求 0x52 是 **IN（0xc0 设备→主机）读 1 字节**，wValue=0、wIndex=4 选择 CarPlay
                // 配置集。此前一度改为 OUT（0x40 写 0x01），真机复验显示会被 iPhone 直接以 transferred=-1
                // 拒绝，反而更早失败——OUT 是错误假设，IN 才是让 iPhone 重枚举出 CarPlay 配置的正确请求。
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                onDiagnostic(
                    "CarPlay reenumeration request dir=IN bRequest=0x52 " +
                        "wIndex=0x${CARPLAY_CONFIGURATION_INDEX.toString(16)} transferred=$transferred",
                )
                if (transferred != response.size) {
                    // iOS 12 与 15 即使解锁也会拒绝该请求（#509、#455）；锁屏的 iPhone 同样拒绝（#100）。
                    // 记录 iPhone 暴露的信息以区分二者。
                    runCatching { onDiagnostic(modeRejectionDiagnostic(connection, device)) }
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            selectConfiguration(connection, device, configuration)
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.label} iface=${usbMux.id}" +
                    " alt=${UsbDescriptorCompat.alternateSetting(usbMux)} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second, onDiagnostic)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    /** Selects [configuration], detaching head-unit drivers that block the switch (#518). */
    private fun selectConfiguration(connection: UsbDeviceConnection, device: UsbDevice, configuration: UsbConfigurationView) {
        // API 17-20 框架不暴露配置值:先从 raw descriptors 解析出实际 SET_CONFIGURATION 目标值,
        // 解析不到时降级为旧的"切换失败也照常 claim"行为(卸载驱动逻辑需要可比对的目标值)。
        val target = configuration.id.takeIf { it > 0 }
            ?: UsbDescriptorCompat.configurationValues(connection.rawDescriptors).getOrNull(configuration.index)
        if (target == null) {
            if (!UsbDescriptorCompat.selectConfiguration(connection, device, configuration)) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "setConfiguration ${configuration.label} reported failure; claiming anyway",
                )
            }
            return
        }
        val interfaces = UsbDescriptorCompat.configurations(device)
            .flatMap { it.interfaces }
            .associateBy { it.id }
        val outcome = UsbConfigurationSelection.select(
            target = target,
            interfaceIds = interfaces.keys.toList(),
            setConfiguration = { UsbDescriptorCompat.selectConfiguration(connection, device, configuration) },
            activeConfiguration = { activeConfiguration(connection) },
            // 强制 claim 会分离内核驱动;release 之后接口保持未绑定
            detach = { id ->
                val usbInterface = interfaces.getValue(id)
                connection.claimInterface(usbInterface, true).also { if (it) connection.releaseInterface(usbInterface) }
            },
        ) ?: return
        runCatching { onDiagnostic(outcome.diagnostic()) }
        if (outcome.wrongConfiguration) {
            throw IphoneUsbException.DeviceUnavailable(
                "Android could not switch the iPhone to USB configuration ${configuration.label} " +
                    "(still ${outcome.after}); a head-unit driver may hold it",
            )
        }
        if (!outcome.selected) {
            Log.w(IphoneCarPlayConfiguration.TAG, "setConfiguration ${configuration.label} reported failure; claiming anyway")
        }
    }

    /** iPhone 当前配置值(标准 GET_CONFIGURATION),读不到时为 null。 */
    private fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = connection.controlTransfer(
            USB_STANDARD_DEVICE_IN, GET_CONFIGURATION_REQUEST, 0, 0, value, value.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return if (read == value.size) value[0].toInt() and 0xff else null
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    /** The iPhone's current USB mode (usbmuxd's 0x45 request) and every configuration it exposes. */
    private fun modeRejectionDiagnostic(connection: UsbDeviceConnection, device: UsbDevice): String {
        val mode = ByteArray(USB_MODE_RESPONSE_LENGTH)
        val read = connection.controlTransfer(
            USB_VENDOR_DEVICE_IN, GET_USB_MODE_REQUEST, 0, 0, mode, mode.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return usbModeDiagnostic(read, mode, UsbDescriptorCompat.deviceVersion(device),
            IphoneCarPlayConfiguration.describeAll(device))
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val USB_STANDARD_DEVICE_IN = 0x80
        private const val GET_CONFIGURATION_REQUEST = 0x08
        private const val GET_USB_MODE_REQUEST = 0x45
        private const val USB_MODE_RESPONSE_LENGTH = 4
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

        /** usbmuxd reads 3:3:3:0 in the initial mode and 5:3:3:0 once a mode is set. */
        /** iOS 12 and 15 answer the mode request with 3 bytes instead of 4 (#509, #455); keep them. */
        internal fun usbModeDiagnostic(read: Int, mode: ByteArray, deviceVersion: String?, configurations: List<String>): String {
            val bytes = mode.copyOf(read.coerceIn(0, mode.size)).joinToString(":") { (it.toInt() and 0xff).toString() }
            val current = when (read) {
                mode.size -> bytes
                in 1 until mode.size -> "$bytes (short:$read)"
                else -> "unreadable($read)"
            }
            return "CarPlay USB mode request rejected; current mode=$current " +
                "usbVersion=${deviceVersion ?: "unknown"} configurations=$configurations"
        }
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var pendingRead: UsbRequest? = null
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    // API 17 迁移：requestWait(long) 重载是 API 26 新增。API 26+ 直接调用；
    // API 17-25 由守护线程调用阻塞式 requestWait()（API 12+），完成的请求经队列交给读者。
    private val legacyRequestWait = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    private val completedRequests = if (legacyRequestWait) LinkedBlockingQueue<UsbRequest>() else null
    private val waitThreadExited = UsbRequest() // 哨兵：requestWait() 返回 null，连接已错误/关闭
    private val requestWaitThread = if (legacyRequestWait) {
        Thread({ drainCompletedRequests() }, "usbmux-request-wait").apply {
            isDaemon = true
            start()
        }
    } else null

    /**
     * API 17 迁移：守护线程循环调用阻塞式 requestWait()，把完成的请求放入队列；
     * 返回 null 表示连接错误/关闭，放入哨兵唤醒读者。close() 后 connection 关闭会使
     * requestWait() 返回，线程随之退出（daemon 线程不阻止 JVM 退出）。
     */
    private fun drainCompletedRequests() {
        while (true) {
            val completed = try {
                connection.requestWait()
            } catch (_: RuntimeException) {
                break
            }
            if (completed == null) break
            completedRequests?.offer(completed)
        }
        completedRequests?.offer(waitThreadExited)
    }

    /**
     * 返回 null 表示超时（请求保持排队）；[timedOut] 标记本次 null 是否为超时
     * （main 语义：超时走 drainTimedOutRead 回读，requestWait 返回 null 才断会话）。
     * 哨兵或 requestWait() 返回 null 时判定会话失败。
     */
    private fun awaitCompletedRead(timeoutMillis: Long, timedOut: AtomicBoolean): UsbRequest? {
        if (!legacyRequestWait) {
            return try {
                connection.requestWait(timeoutMillis)
            } catch (_: TimeoutException) {
                timedOut.set(true)
                null
            }
        }
        val queue = completedRequests ?: return null
        val polled = try {
            queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (polled === waitThreadExited) throw failSession("Android returned no USBMUX read request")
        if (polled == null) timedOut.set(true)
        return polled
    }

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val request = UsbRequest()
        var initialized = false
        try {
            if (!request.initialize(connection, inEndpoint)) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                )
            }
            initialized = true
            val buffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
            val queueResult = synchronized(stateLock) {
                checkOpenLocked()
                pendingRead = request
                readQueuePolicy.queue(buffer, ::checkOpenLocked) { request.queueRead(it) }
            }
            if (!queueResult.queued) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis)} " +
                        "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                )
            }
            // The policy remembers an accepted fallback, so this event occurs once per pipe.
            if (queueResult.fallbackBytes != null) runCatching {
                onDiagnostic(
                    "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queueResult.firstBytes} " +
                        "fallbackBytes=${queueResult.fallbackBytes}",
                )
            }
            val timedOut = AtomicBoolean(false)
            val completed = awaitCompletedRead(timeoutMillis, timedOut)
            if (completed == null) {
                // API 17 迁移：超时（legacy 队列 poll 超时或 API 26+ TimeoutException）。
                // 读可能在超时与 cancel 之间已完成（#610/#301），回读其字节而不是断开会话；
                // 非超时的 null（队列层无结果）仍按 main 语义断开会话。
                if (!timedOut.get()) throw failSession("Android returned no USBMUX read request")
                return@synchronized drainTimedOutRead(request, buffer)
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            return@synchronized ByteArray(buffer.position()).also {
                buffer.flip()
                buffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        } finally {
            synchronized(stateLock) {
                if (pendingRead === request) pendingRead = null
            }
            if (initialized) request.cancel()
            request.close()
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        connection.close()
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    /**
     * 取消已超时的读并从 Android 收回(移植自 main #625,#610/#301)。
     * 超时与 cancel 之间完成的读已无法取消:它正在返回途中。这一竞态曾在 Telechips 与
     * DiLink 5.1 车机上每隔几分钟断开整个会话。无论是否完成都排空该请求,并把它携带的
     * 字节返回,保持 USBMUX 流完整。
     */
    private fun drainTimedOutRead(request: UsbRequest, buffer: ByteBuffer): ByteArray? {
        val cancelled = request.cancel()
        // API 17 迁移：经 awaitCompletedRead 统一等待（legacy 队列或 API 26+ 定时重载），
        // 排空阶段超时一律按 main 文案断开（cancelled 与否区分原因）
        val completed = if (legacyRequestWait) {
            awaitCompletedRead(CANCEL_DRAIN_TIMEOUT_MILLIS, AtomicBoolean(false))
        } else {
            try {
                connection.requestWait(CANCEL_DRAIN_TIMEOUT_MILLIS)
            } catch (_: TimeoutException) {
                null
            }
        } ?: throw failSession(
            if (cancelled) "Timed out draining cancelled USBMUX read request"
            else "Android could not cancel timed out USBMUX read request",
        )
        if (completed !== request) {
            throw failSession("Android did not drain the cancelled USBMUX read request")
        }
        if (buffer.position() == 0) return null
        return ByteArray(buffer.position()).also {
            buffer.flip()
            buffer.get(it)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
        const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
