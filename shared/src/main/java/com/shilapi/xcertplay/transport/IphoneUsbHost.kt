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
 * configuration `6`. Android has neither LIVI's sysfs configuration control nor a synchronous
 * re-enumeration primitive, so the configuration switch goes through the `USBDEVFS_SETCONFIGURATION`
 * ioctl (API 17-20 has no framework `setConfiguration`) and this class closes the first connection,
 * requiring the caller to receive, re-authorize, and pass the new [UsbDevice] back for the data-path
 * open. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    /** claim 失败时的接口探针只做一次：强制 claim 会分离内核驱动，不能每次重试都跑。 */
    private val claimProbeDone = AtomicBoolean(false)

    /**
     * 最近一次配置切换的结果（内核/控制传输、目标的 detach/ioctl 返回值）。
     *
     * 随 claim 失败信息一起显示：屏幕日志是滚动窗口，`usb config …` 那行会被后面长长的
     * `usbmux pre-claim`/`deviceIfaces` 顶出可视区，真机复验时看不见切换到底走没走通。
     */
    @Volatile private var lastConfigurationSwitch: String? = null

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

    /**
     * Every USB device Android lists, as `vid:pid node` like [Ch341UsbHost.describe], sorted. When no iPhone is
     * listed, this shows whether Android sees anything on the head unit's port at all.
     */
    fun listedDevices(): List<String> =
        usbManager.deviceList.values.map(Ch341UsbHost::describe).sorted()

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
        var connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device, connection.rawDescriptors)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            connection = selectConfiguration(connection, device, configuration)
            val candidates = IphoneCarPlayConfiguration.usbMuxCandidates(device, configuration)
            if (candidates.isEmpty()) {
                throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            }
            // 复验需要的现场：切换目标、内核报告的活动配置(标准 GET_CONFIGURATION)、描述符里的
            // 配置值、全部 USBMUX 候选、设备全部接口。API 17-20 没有框架 setConfiguration，
            // 切配置只能靠原始控制传输，内核 actconfig 未必跟着刷新，所以候选里可能只有一部分
            // 能 claim 成功——这也正是要把两边都打出来的原因。
            onDiagnostic(
                "usbmux pre-claim config=${configuration.label} id=${configuration.id} " +
                    "index=${configuration.index} active=${activeConfiguration(connection)} " +
                    "rawValues=${UsbDescriptorCompat.configurationValues(connection.rawDescriptors)} " +
                    "candidates=${candidates.map { it.id }} " +
                    "deviceIfaces=[${describeDeviceInterfaces(device)}]",
            )
            // #518：厂商内核可能在配置切换后、claim 之前重新绑定驱动，单次 claim 会失败。
            // NCM 路径早已用 5 次 /100ms 重试兜底，USBMUX 这一路在此对齐。
            val claim = claimUsbMuxInterface(connection, candidates)
                ?: throw IphoneUsbException.DeviceUnavailable(
                    "Android could not claim any USBMUX interface " +
                        "(candidates=${candidates.map { it.id }} " +
                        "active=${activeConfiguration(connection)} " +
                        "claimProbe=[${probeClaimableInterfaces(connection, device)}] " +
                        "switch=[${lastConfigurationSwitch ?: "unknown"}] " +
                        "deviceIfaces=[${describeDeviceInterfaces(device)}])",
                )
            claimedInterface = claim.usbInterface
            return Iap2UsbSession(connection, claim.endpoints.first, claim.endpoints.second, onDiagnostic)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    /**
     * 选择 [configuration],分离阻塞切换的车机内核驱动(#518)。返回从此刻起应使用的连接:
     * 分离操作会用一条新连接替换 [connection]。
     */
    private fun selectConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configuration: UsbConfigurationView,
    ): UsbDeviceConnection {
        // API 17-20 框架不暴露配置值:先从 raw descriptors 解析出实际 SET_CONFIGURATION 目标值,
        // 解析不到时降级为旧的"切换失败也照常 claim"行为(卸载驱动逻辑需要可比对的目标值)。
        val target = configuration.id.takeIf { it > 0 }
            ?: UsbDescriptorCompat.configurationValues(connection.rawDescriptors).getOrNull(configuration.index)
        if (target == null) {
            val selected = UsbDescriptorCompat.selectConfiguration(connection, device, configuration)
            val diagnostic = "usb config select target=unknown label=${configuration.label} " +
                "ok=$selected active=${activeConfiguration(connection)}"
            lastConfigurationSwitch = diagnostic
            onDiagnostic(diagnostic)
            if (!selected) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "setConfiguration ${configuration.label} reported failure; claiming anyway",
                )
            }
            return connection
        }
        val interfaces = UsbDescriptorCompat.configurations(device, connection.rawDescriptors)
            .flatMap { it.interfaces }
            .associateBy { it.id }
        var current = connection
        try {
            val outcome = UsbConfigurationSelection.select(
                target = target,
                interfaceIds = interfaces.keys.toList(),
                setConfiguration = { switchConfiguration(current, device, configuration, target) },
                activeConfiguration = { activeConfiguration(current) },
                // 强制 claim 分离内核驱动;保持持有直到 reopen。
                detach = { id -> current.claimInterface(interfaces.getValue(id), true) },
                // 关闭会丢弃 claim 而无需 releaseInterface() 触发的内核重新绑定。
                reopen = {
                    current.close()
                    current = usbManager.openDevice(device)
                        ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not reopen the iPhone")
                },
            ) ?: run {
                // select() 只在首次 SET_CONFIGURATION 就成功时返回 null，这里补一条屏幕诊断，
                // 否则切换过程在屏幕上完全不可见（真机复验只能靠它判断切到了哪个配置）。
                val diagnostic = "usb config select target=$target ok=true detached=none " +
                    "active=${activeConfiguration(current)}"
                lastConfigurationSwitch = diagnostic
                onDiagnostic(diagnostic)
                return current
            }
            val switchDiagnostic = outcome.diagnostic()
            lastConfigurationSwitch = switchDiagnostic
            runCatching { onDiagnostic(switchDiagnostic) }
            if (outcome.wrongConfiguration) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not switch the iPhone to USB configuration ${configuration.label} " +
                        "(still ${outcome.after}); a head-unit driver may hold it",
                )
            }
            if (!outcome.selected) {
                Log.w(IphoneCarPlayConfiguration.TAG, "setConfiguration ${configuration.label} reported failure; claiming anyway")
            }
            return current
        } catch (error: Throwable) {
            // 调用方会关闭它传入的连接;替换出来的连接在这里关闭。
            if (current !== connection) current.close()
            throw error
        }
    }

    /**
     * 切换到 [target] 配置。
     *
     * API 17-20 没有框架 `setConfiguration`,默认走内核级 `USBDEVFS_SETCONFIGURATION` ioctl:内核会
     * 重新读取配置、重建 `dev->actconfig`,之后 `claimInterface` 才能看到新配置里的接口(这正是
     * "iPhone 切到 6、内核仍认为只有 0 号接口、claim 全部失败"那个卡点的解)。等价于 LIVI 写 sysfs
     * bConfigurationValue。只有 ioctl 失败或拿不到文件描述符时才退回原始 SET_CONFIGURATION 控制传输
     * ——后者只切设备、不刷新内核,仅作兜底。
     */
    private fun switchConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configuration: UsbConfigurationView,
        target: Int,
    ): Boolean {
        val fileDescriptor = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (!NativeUsbConfigurationKernel.available) {
            // 库没打进 APK 时静默回退最容易被误读成"内核拒绝切换"；显式说清楚。
            runCatching { onDiagnostic("usb config kernel library unavailable; kernel path skipped") }
        }
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = fileDescriptor,
            target = target,
            kernelDetach = { descriptor ->
                runCatching { NativeUsbConfigurationKernel.detachKernelDrivers(descriptor) }.getOrDefault(-1)
            },
            kernelSet = { descriptor, value ->
                runCatching { NativeUsbConfigurationKernel.setConfiguration(descriptor, value) }.getOrDefault(-1)
            },
            controlSet = { UsbDescriptorCompat.selectConfiguration(connection, device, configuration) },
        )
        val diagnostic = outcome.diagnostic(target, fileDescriptor, activeConfiguration(connection))
        lastConfigurationSwitch = diagnostic
        runCatching { onDiagnostic(diagnostic) }
        return outcome.selected
    }

    /** iPhone 当前配置值(标准 GET_CONFIGURATION),读不到时为 null。 */
    private fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = connection.controlTransfer(
            USB_STANDARD_DEVICE_IN, GET_CONFIGURATION_REQUEST, 0, 0, value, value.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return if (read == value.size) value[0].toInt() and 0xff else null
    }

    /**
     * #518：厂商内核驱动可能在 Android 强制分离与 claim 之间重新绑定接口，单次 claim 会失败。
     * NCM 路径用 5 次 /100ms 重试兜底，USBMUX 路径此前只 claim 一次；此处补齐。返回成功所用次数。
     */
    private fun claimWithRetry(connection: UsbDeviceConnection, usbInterface: UsbInterface): Int? {
        for (attempt in 1..USB_CLAIM_ATTEMPTS) {
            if (connection.claimInterface(usbInterface, true)) return attempt
            if (attempt < USB_CLAIM_ATTEMPTS) Thread.sleep(USB_CLAIM_RETRY_MILLIS)
        }
        return null
    }

    /** 一次成功的 USBMUX claim：接口本体 + 它的 bulk 端点对。 */
    private class UsbMuxClaim(
        val usbInterface: UsbInterface,
        val endpoints: Pair<UsbEndpoint, UsbEndpoint>,
    )

    /**
     * 依次尝试 [candidates]，返回第一个 claim 成功且带 bulk 端点对的 USBMUX 接口。
     *
     * 单个候选被拒通常意味着它不属于内核当前认识的配置（API 17-20 只能用原始 SET_CONFIGURATION
     * 控制传输切换，内核 actconfig 不一定跟着刷新），所以跨配置继续试同族接口；端点缺失的候选
     * 直接跳过，避免"claim 成功却没有管道"。
     */
    private fun claimUsbMuxInterface(
        connection: UsbDeviceConnection,
        candidates: List<UsbInterface>,
    ): UsbMuxClaim? {
        for (candidate in candidates) {
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(candidate) ?: continue
            val attempts = claimWithRetry(connection, candidate)
            if (attempts == null) {
                onDiagnostic("usbmux claim refused iface=${candidate.id} endpoints=${candidate.endpointCount}")
                continue
            }
            onDiagnostic(
                "usbmux claimed iface=${candidate.id} attempts=$attempts " +
                    "out=${describeUsbEndpoint(endpoints.first)} in=${describeUsbEndpoint(endpoints.second)}",
            )
            return UsbMuxClaim(candidate, endpoints)
        }
        return null
    }

    /**
     * claim 全部失败后的现场探针：逐个接口号强制 claim 再立刻释放，报告哪些能拿到。
     *
     * 这一步区分两种根因：只有 0 号能拿到 → 内核 actconfig 里只有 1 个接口（配置没真正切过去）；
     * 若干号能拿到、只有目标号被拒 → 该接口被驱动或另一个 usbfs 持有者占着。
     * 只做一次（强制 claim 会分离内核驱动）。
     */
    private fun probeClaimableInterfaces(connection: UsbDeviceConnection, device: UsbDevice): String {
        if (!claimProbeDone.compareAndSet(false, true)) return "skipped(already-probed)"
        return (0 until device.interfaceCount)
            .map(device::getInterface)
            .distinctBy { it.id }
            .sortedBy { it.id }
            .joinToString(",") { candidate ->
                val claimed = connection.claimInterface(candidate, true)
                if (claimed) connection.releaseInterface(candidate)
                "${candidate.id}=${if (claimed) "ok" else "fail"}"
            }
    }

    /** 设备暴露的全部接口 `id/alt:class.subclass.protocol x endpoints`，用于诊断 claim 失败现场。 */
    private fun describeDeviceInterfaces(device: UsbDevice): String =
        (0 until device.interfaceCount).joinToString(",") { index ->
            val usbInterface = device.getInterface(index)
            val alt = UsbDescriptorCompat.alternateSetting(usbInterface)
            "${usbInterface.id}/${if (alt < 0) "?" else alt}:" +
                "${usbInterface.interfaceClass.toString(16)}." +
                "${usbInterface.interfaceSubclass.toString(16)}." +
                "${usbInterface.interfaceProtocol.toString(16)}x${usbInterface.endpointCount}"
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
            IphoneCarPlayConfiguration.describeAll(device, connection.rawDescriptors))
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

        /** #518：USBMUX claim 重试次数与间隔（与 NCM 路径的 retryUsbSetup 对齐）。 */
        private const val USB_CLAIM_ATTEMPTS = 5
        private const val USB_CLAIM_RETRY_MILLIS = 100L

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
    private var pendingReadBuffer: ByteBuffer? = null
    /** 已把 [pendingRead] 提交给内核；为 true 时下一次读只等结果，绝不重复排队。 */
    private var readQueued = false
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

    /**
     * Returns null only when no completed USB request arrives before [timeoutMillis].
     *
     * 超时**不**取消、**不**关闭排在 usbfs 里的读请求：内核里那条 URB 依然有效，下一次调用继续等它，
     * 数据一到就原样交回，既不丢字节也不重建请求。旧实现在超时后 cancel()+close()，一旦 cancel
     * 被厂商内核拒绝，就会释放仍在队列里的 URB/native 缓冲，内核完成回调随后写入已释放内存，真机
     * 表现是整个进程被 SIGSEGV 打死（应用直接退回 DiPlay 主页，会话日志戛然而止）；
     * 而 cancel 成功的路径又会把手机侧正常的静默当成会话死刑。NCM 桥早已采用同样的复用模型。
     */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val queuedRead = try {
            acquireQueuedRead(timeoutMillis)
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        queuedRead.fallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        val request = queuedRead.request
        val buffer = queuedRead.buffer
        val timedOut = AtomicBoolean(false)
        val completed = try {
            awaitCompletedRead(timeoutMillis, timedOut)
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
        if (completed == null) {
            // 非超时的 null 只来自 requestWait() 返回 null（连接已错误/关闭），仍按会话失败处理。
            if (!timedOut.get()) throw failSession("Android returned no USBMUX read request")
            // 超时：请求留在队列里，读到的字节为 0，本次按"暂无数据"返回。
            return null
        }
        if (completed !== request) {
            throw failSession("Android completed an unexpected USB request")
        }
        synchronized(stateLock) { readQueued = false }
        val transferred = buffer.position()
        if (transferred <= 0) return null
        if (transferred > buffer.capacity()) {
            throw failSession("Android reported $transferred USBMUX bytes for a ${buffer.capacity()}-byte buffer")
        }
        return ByteArray(transferred).also {
            buffer.flip()
            buffer.get(it)
        }
    }

    /** 复用仍未完成的读请求；只有确实没有排队时才新建并提交一次。 */
    private fun acquireQueuedRead(timeoutMillis: Long): QueuedUsbRead = synchronized(stateLock) {
        checkOpenLocked()
        val request = pendingRead ?: UsbRequest().also { created ->
            if (!created.initialize(connection, inEndpoint)) {
                created.close()
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                )
            }
            pendingRead = created
            pendingReadBuffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
        }
        val buffer = pendingReadBuffer
            ?: throw failSession("USBMUX read buffer state was lost")
        var fallback: UsbReadQueueResult? = null
        if (!readQueued) {
            buffer.clear()
            val queued = readQueuePolicy.queue(buffer, ::checkOpenLocked) { request.queueRead(it) }
            if (!queued.queued) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis)} " +
                        "firstBytes=${queued.firstBytes} fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                )
            }
            readQueued = true
            if (queued.fallbackBytes != null) fallback = queued
        }
        QueuedUsbRead(request, buffer, fallback)
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            val queued = pendingRead
            pendingRead = null
            pendingReadBuffer = null
            readQueued = false
            queued
        }
        // 顺序很关键：先撤销仍在内核队列里的 URB，再关连接（内核据此丢弃残留请求），最后才释放
        // 请求对象。反过来在 URB 还在队列时 close()，内核完成回调会写到已释放的 native 缓冲。
        requestToCancel?.let { queued -> runCatching { queued.cancel() } }
        runCatching { connection.close() }
        requestToCancel?.let { queued -> runCatching { queued.close() } }
    }

    /**
     * 诊断探针：NCM 二次 openDevice 之后，确认 USBMUX 这一路（首个连接）的控制通道是否仍存活。
     *
     * C140A API 17 内核双 UsbDeviceConnection 可能导致第二次 open / SET_INTERFACE 重置首个连接的
     * 端点或接口，使 USBMUX bulk-OUT 握手无限阻塞。这里发一个无害的标准 GET_STATUS（recipient=设备）
     * 控制传输：返回 2 字节说明首个连接控制通道仍可用；返回 -1/异常/超时则说明它已被破坏 —— 即可
     * 确证双连接冲突，而非 iPhone 不回版本包。超时 1s 兜底，绝不无限阻塞。
     */
    fun probeControlChannel(): String = runCatching {
        val buffer = ByteArray(2)
        val transferred = connection.controlTransfer(
            PROBE_STANDARD_DEVICE_IN, PROBE_GET_STATUS_REQUEST, 0, 0, buffer, buffer.size, 1000,
        )
        if (transferred == buffer.size) "ok transferred=$transferred" else "unexpected transferred=$transferred"
    }.getOrElse { "failed: ${it.message}" }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    /** 记录并返回会话级失败（首次失败即定型，后续包装保持原始原因）。 */
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

    /** 一次已被内核接受的读请求；请求与缓冲区必须成对存活，直到完成或 [close]。 */
    private class QueuedUsbRead(
        val request: UsbRequest,
        val buffer: ByteBuffer,
        val fallback: UsbReadQueueResult?,
    )

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
        // 探针：标准 GET_STATUS（recipient=设备），无害且不改变设备状态。
        private const val PROBE_STANDARD_DEVICE_IN = 0x80
        private const val PROBE_GET_STATUS_REQUEST = 0x00
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
