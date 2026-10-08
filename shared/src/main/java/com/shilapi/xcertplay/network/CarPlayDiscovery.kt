package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 服务发现。
 *
 * 使用 mDNS/DNS-SD 在外部 Wi-Fi 网络中发现 iPhone。
 *
 * API 17 注意事项：
 * - 使用 JmDNS 库进行 mDNS 发现（`javax.jmdns` 包）；
 * - NsdManager 在 API 16+ 可用但功能有限，这里统一走 JmDNS；
 * - 需要手动管理 MulticastLock 才能收到多播包。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class CarPlayDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "CarPlayDiscovery"

        /** CarPlay 服务类型。 */
        const val CARPLAY_SERVICE_TYPE = "_carplay._tcp.local."

        /** 默认 CarPlay 端口。 */
        const val DEFAULT_CARPLAY_PORT = 7000

        /** 发现超时（毫秒）。 */
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val isDiscovering = AtomicBoolean(false)
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmDNS: JmDNS? = null
    private var discoveryListener: DiscoveryListener? = null
    // jmdns 3.5.8 没有 removeAllServiceListeners()，需保存引用逐个注销
    private var serviceListener: ServiceListener? = null

    /**
     * 发现回调接口。
     */
    interface DiscoveryListener {
        /** 发现新设备。 */
        fun onDeviceDiscovered(device: DiscoveredDevice)

        /** 设备丢失。 */
        fun onDeviceLost(deviceName: String)

        /** 发现失败。 */
        fun onDiscoveryFailed(error: Exception)

        /** 发现完成（超时结束）。 */
        fun onDiscoveryComplete()
    }

    /**
     * 开始发现 CarPlay 设备。
     */
    @Suppress("DEPRECATION")
    fun startDiscovery(listener: DiscoveryListener) {
        if (isDiscovering.getAndSet(true)) {
            Log.w(TAG, "Discovery already in progress")
            return
        }

        discoveryListener = listener
        discoveredDevices.clear()

        try {
            // 获取 MulticastLock
            acquireMulticastLock()

            // 用当前 Wi-Fi IP 初始化 JmDNS
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0

            val inetAddress = if (ipAddress != 0) {
                val bytes = byteArrayOf(
                    (ipAddress and 0xff).toByte(),
                    (ipAddress shr 8 and 0xff).toByte(),
                    (ipAddress shr 16 and 0xff).toByte(),
                    (ipAddress shr 24 and 0xff).toByte()
                )
                InetAddress.getByAddress(bytes)
            } else {
                InetAddress.getLocalHost()
            }

            jmDNS = JmDNS.create(inetAddress)

            // 添加服务监听器（保存引用以便 stopDiscovery 注销）
            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    Log.d(TAG, "Service added: ${event.name}")
                    // 请求完整服务信息（true 表示同步获取）
                    event.dns.requestServiceInfo(event.type, event.name, true)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    // jmdns 3.5.8：inetAddresses 属性不存在，改用 getInetAddress()
                    val hostAddress = runCatching { info.inetAddress?.hostAddress }.getOrNull()
                        ?: runCatching { info.hostAddresses.firstOrNull() }.getOrNull()
                    // getPropertyNames() 返回 Enumeration，Kotlin 扩展属性不适用
                    val properties = info.propertyNames?.toList()?.associateWith {
                        info.getPropertyString(it) ?: ""
                    } ?: emptyMap()
                    val device = DiscoveredDevice(
                        name = event.name,
                        host = hostAddress,
                        port = info.port,
                        type = event.type,
                        properties = properties
                    )

                    discoveredDevices[device.name] = device
                    handler.post {
                        discoveryListener?.onDeviceDiscovered(device)
                    }

                    Log.d(TAG, "Service resolved: ${device.name} at ${device.host}:${device.port}")
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    discoveredDevices.remove(event.name)
                    handler.post {
                        discoveryListener?.onDeviceLost(event.name)
                    }
                    Log.d(TAG, "Service removed: ${event.name}")
                }
            }
            serviceListener = listener
            jmDNS?.addServiceListener(
                CARPLAY_SERVICE_TYPE,
                listener
            )

            // 设置超时
            handler.postDelayed({
                stopDiscovery()
                handler.post {
                    discoveryListener?.onDiscoveryComplete()
                }
            }, DISCOVERY_TIMEOUT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            isDiscovering.set(false)
            releaseMulticastLock()
            handler.post {
                listener.onDiscoveryFailed(e)
            }
        }
    }

    /**
     * 停止发现。
     */
    fun stopDiscovery() {
        if (!isDiscovering.getAndSet(false)) {
            return
        }

        try {
            // jmdns 3.5.8：removeAllServiceListeners() 不存在，逐个注销已保存的监听器
            serviceListener?.let { listener ->
                runCatching { jmDNS?.removeServiceListener(CARPLAY_SERVICE_TYPE, listener) }
            }
            serviceListener = null
            jmDNS?.close()
            jmDNS = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing JmDNS", e)
        }

        releaseMulticastLock()
    }

    /**
     * 获取已发现的设备列表。
     */
    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        return discoveredDevices.values.toList()
    }

    /**
     * 获取 MulticastLock。API 17 需要此锁来接收多播包。
     */
    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

        multicastLock = wifiManager.createMulticastLock(TAG).apply {
            setReferenceCounted(true)
            acquire()
        }

        Log.d(TAG, "MulticastLock acquired")
    }

    /**
     * 释放 MulticastLock。
     */
    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "MulticastLock released")
            }
        }
        multicastLock = null
    }
}

/**
 * 发现的 CarPlay 设备。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
data class DiscoveredDevice(
    val name: String,
    val host: String?,
    val port: Int,
    val type: String,
    val properties: Map<String, String>
) {
    /**
     * 获取主机地址（优先 mDNS 解析结果，其次 TXT 记录中的 ip 字段）。
     */
    fun getHostAddress(): String? {
        return host?.split(".")?.firstOrNull() ?: properties["ip"]
    }

    /**
     * 检查设备是否可用。
     */
    fun isAvailable(): Boolean {
        return !host.isNullOrBlank() && port > 0
    }
}
