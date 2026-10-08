package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.net.Socket

/**
 * 外部 Wi-Fi 网络优化配置。
 *
 * 提供 Socket 参数调优与 Wi-Fi/CPU 保活锁管理，
 * 保证低版本设备（API 17）上无线 CarPlay 的吞吐与时延。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
object ExternalWifiOptimization {

    /**
     * Socket 优化参数：降低时延、提高吞吐、检测断线。
     */
    fun optimizeSocket(socket: Socket) {
        // TCP 无延迟 - 减少小包时延
        socket.tcpNoDelay = true

        // 保持连接 - 检测断线
        socket.keepAlive = true

        // 接收缓冲区 - 提高吞吐量
        socket.receiveBufferSize = 256 * 1024 // 256KB

        // 发送缓冲区
        socket.sendBufferSize = 256 * 1024 // 256KB

        // 超时设置
        socket.soTimeout = 30_000 // 30 秒
    }

    /**
     * Wi-Fi 管理器优化：获取 MulticastLock 与 WakeLock。
     *
     * 调用方负责在会话结束时释放返回的锁。
     */
    fun acquireWifiLocks(context: Context): Pair<WifiManager.MulticastLock, PowerManager.WakeLock?> {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 获取 MulticastLock 确保 mDNS 工作
        val multicastLock = wifiManager.createMulticastLock("DiPlay").apply {
            setReferenceCounted(true)
            acquire()
        }

        // 获取 WakeLock 防止休眠
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DiPlay:Wifi"
        )?.apply {
            acquire(30 * 60 * 1000L) // 30 分钟
        }

        return multicastLock to wakeLock
    }
}
