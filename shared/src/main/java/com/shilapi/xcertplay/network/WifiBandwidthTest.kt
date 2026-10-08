package com.shilapi.xcertplay.network

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 外部 Wi-Fi 带宽测试。
 *
 * 通过 TCP 收发验证链路是否满足 CarPlay 视频流要求。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class WifiBandwidthTest {

    /**
     * 测试到指定主机的网络带宽。
     *
     * @param host 目标主机
     * @param port 目标端口
     */
    fun testBandwidth(host: String, port: Int): BandwidthResult {
        val startTime = System.currentTimeMillis()
        val testData = ByteArray(1024 * 1024) // 1MB 测试数据
        var totalBytes = 0L

        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)

            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // 发送测试数据
            val sendStart = System.currentTimeMillis()
            outputStream.write(testData)
            outputStream.flush()
            val sendTime = System.currentTimeMillis() - sendStart

            // 接收响应
            val receiveStart = System.currentTimeMillis()
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                totalBytes += read
            }
            val receiveTime = System.currentTimeMillis() - receiveStart

            socket.close()

            val totalTime = System.currentTimeMillis() - startTime
            val uploadSpeed = if (sendTime > 0) (testData.size * 8.0) / (sendTime * 1000) else 0.0 // Mbps
            val downloadSpeed = if (receiveTime > 0) (totalBytes * 8.0) / (receiveTime * 1000) else 0.0 // Mbps

            return BandwidthResult(
                uploadSpeedMbps = uploadSpeed,
                downloadSpeedMbps = downloadSpeed,
                latencyMs = totalTime,
                success = true
            )
        } catch (e: Exception) {
            return BandwidthResult(
                uploadSpeedMbps = 0.0,
                downloadSpeedMbps = 0.0,
                latencyMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message
            )
        }
    }
}

/**
 * 带宽测试结果。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
data class BandwidthResult(
    val uploadSpeedMbps: Double,
    val downloadSpeedMbps: Double,
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null
)
