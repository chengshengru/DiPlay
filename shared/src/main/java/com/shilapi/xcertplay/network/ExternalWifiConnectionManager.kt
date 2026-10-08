package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 外部 Wi-Fi 连接管理器。
 *
 * 管理通过外部 Wi-Fi 路由器进行的 CarPlay 连接：
 * 设备发现（[CarPlayDiscovery]）、TCP 连接、心跳保活与 WakeLock 管理。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class ExternalWifiConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ExternalWifiConnMgr"

        /** 连接超时（毫秒）。 */
        const val CONNECTION_TIMEOUT_MS = 10_000L

        /** 心跳间隔（毫秒）。 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** 最大重试次数。 */
        const val MAX_RETRY_COUNT = 3

        /** 重试延迟（毫秒）。 */
        const val RETRY_DELAY_MS = 2_000L
    }

    /**
     * 连接状态。
     */
    enum class ConnectionState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * 连接回调接口。
     */
    interface ExternalWifiConnectionListener {
        fun onStateChanged(state: ConnectionState)
        fun onDeviceDiscovered(device: DiscoveredDevice)
        fun onConnected(device: DiscoveredDevice)
        fun onDisconnected(reason: String)
        fun onError(error: Exception)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)
    private var currentSocket: Socket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionListener: ExternalWifiConnectionListener? = null
    private var discovery: CarPlayDiscovery? = null
    private var heartbeatRunnable: Runnable? = null

    /**
     * 设置连接监听器。
     */
    fun setConnectionListener(listener: ExternalWifiConnectionListener) {
        connectionListener = listener
    }

    /**
     * 检查外部 Wi-Fi 是否可用。
     */
    @Suppress("DEPRECATION")
    fun isExternalWifiAvailable(): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return false

        if (!wifiManager.isWifiEnabled) {
            return false
        }

        return ExternalWifiMode.isSuitableForExternalWifi(context)
    }

    /**
     * 获取当前 Wi-Fi 信息。
     */
    fun getWifiInfo(): WifiInfoCompat? {
        return ExternalWifiMode.getWifiInfo(context)
    }

    /**
     * 开始自动发现并连接第一个发现的设备。
     */
    fun startAutoDiscovery() {
        if (!isExternalWifiAvailable()) {
            connectionListener?.onError(
                IllegalStateException("外部 Wi-Fi 不可用，请检查 Wi-Fi 连接")
            )
            return
        }

        updateState(ConnectionState.DISCOVERING)

        discovery = CarPlayDiscovery(context).also {
            it.startDiscovery(object : CarPlayDiscovery.DiscoveryListener {
                override fun onDeviceDiscovered(device: DiscoveredDevice) {
                    connectionListener?.onDeviceDiscovered(device)

                    // 自动连接第一个发现的设备
                    if (connectionState.get() == ConnectionState.DISCOVERING) {
                        connectToDevice(device)
                    }
                }

                override fun onDeviceLost(deviceName: String) {
                    Log.d(TAG, "Device lost: $deviceName")
                }

                override fun onDiscoveryFailed(error: Exception) {
                    updateState(ConnectionState.ERROR)
                    connectionListener?.onError(error)
                }

                override fun onDiscoveryComplete() {
                    if (connectionState.get() == ConnectionState.DISCOVERING) {
                        // 未发现设备
                        updateState(ConnectionState.DISCONNECTED)
                        connectionListener?.onDisconnected("未发现 CarPlay 设备")
                    }
                }
            })
        }
    }

    /**
     * 手动连接到指定 IP。
     */
    fun connectToDevice(host: String, port: Int = CarPlayDiscovery.DEFAULT_CARPLAY_PORT) {
        val device = DiscoveredDevice(
            name = "Manual: $host",
            host = host,
            port = port,
            type = CarPlayDiscovery.CARPLAY_SERVICE_TYPE,
            properties = emptyMap()
        )
        connectToDevice(device)
    }

    /**
     * 连接到发现的设备。
     */
    fun connectToDevice(device: DiscoveredDevice) {
        val host = device.host
        if (host.isNullOrBlank() || !device.isAvailable()) {
            connectionListener?.onError(
                IllegalArgumentException("设备信息不完整: ${device.name}")
            )
            return
        }

        // 停止发现
        discovery?.stopDiscovery()

        updateState(ConnectionState.CONNECTING)

        // 在后台线程中连接
        Thread {
            var retryCount = 0
            var connectedNow = false

            while (retryCount < MAX_RETRY_COUNT && !connectedNow) {
                try {
                    Log.d(TAG, "Connecting to $host:${device.port} (attempt ${retryCount + 1})")

                    val socket = Socket()
                    socket.connect(
                        InetSocketAddress(host, device.port),
                        CONNECTION_TIMEOUT_MS.toInt()
                    )

                    socket.soTimeout = CONNECTION_TIMEOUT_MS.toInt()
                    socket.keepAlive = true
                    socket.tcpNoDelay = true

                    currentSocket = socket
                    connectedNow = true

                    handler.post {
                        connected.set(true)
                        updateState(ConnectionState.CONNECTED)
                        connectionListener?.onConnected(device)
                        startHeartbeat()
                        acquireWakeLock()
                    }

                    Log.d(TAG, "Connected to ${device.name}")

                } catch (e: IOException) {
                    retryCount++
                    Log.w(TAG, "Connection attempt $retryCount failed", e)

                    if (retryCount < MAX_RETRY_COUNT) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }

            if (!connectedNow) {
                handler.post {
                    updateState(ConnectionState.ERROR)
                    connectionListener?.onError(
                        IOException("无法连接到 ${device.name}，已重试 $MAX_RETRY_COUNT 次")
                    )
                }
            }
        }.start()
    }

    /**
     * 断开连接。
     */
    fun disconnect() {
        stopHeartbeat()
        releaseWakeLock()
        discovery?.stopDiscovery()

        try {
            currentSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        }
        currentSocket = null

        connected.set(false)
        updateState(ConnectionState.DISCONNECTED)
        connectionListener?.onDisconnected("用户主动断开")
    }

    /**
     * 获取当前连接状态。
     */
    fun getConnectionState(): ConnectionState = connectionState.get()

    /**
     * 检查是否已连接。
     */
    fun isConnected(): Boolean = connected.get()

    /**
     * 获取当前 Socket。
     */
    fun getSocket(): Socket? = currentSocket

    /**
     * 更新连接状态。
     */
    private fun updateState(newState: ConnectionState) {
        val oldState = connectionState.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "State changed: $oldState -> $newState")
            connectionListener?.onStateChanged(newState)
        }
    }

    /**
     * 启动心跳检测。
     */
    private fun startHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (connected.get()) {
                    val socket = currentSocket
                    if (socket == null || socket.isClosed || !socket.isConnected) {
                        Log.w(TAG, "Connection lost during heartbeat")
                        handler.post {
                            connected.set(false)
                            updateState(ConnectionState.DISCONNECTED)
                            connectionListener?.onDisconnected("连接丢失")
                        }
                    } else {
                        handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                    }
                }
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    /**
     * 停止心跳检测。
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /**
     * 获取 WakeLock 防止 CPU 休眠。
     */
    private fun acquireWakeLock() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DiPlay:ExternalWifi"
        )?.apply {
            acquire(30 * 60 * 1000L) // 30 分钟超时
        }
        Log.d(TAG, "WakeLock acquired")
    }

    /**
     * 释放 WakeLock。
     */
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * 释放资源。
     */
    fun release() {
        disconnect()
        connectionListener = null
    }
}
