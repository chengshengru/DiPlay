package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.compat.ApiLevel

/**
 * 外部 Wi-Fi 连接模式。
 *
 * 用于在低版本 Android（API 17）上通过外部路由器实现无线 CarPlay，
 * 替代 Wi-Fi Direct 高级 API。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
enum class ExternalWifiMode {
    /**
     * 自动检测模式：自动发现同一网络中的 iPhone。
     */
    AUTO,

    /**
     * 手动连接模式：用户手动输入 iPhone IP 地址。
     */
    MANUAL,

    /**
     * 固定端口模式：使用固定的端口号进行连接。
     */
    FIXED_PORT;

    companion object {
        /**
         * 检查当前网络是否适合外部 Wi-Fi 模式。
         */
        @Suppress("DEPRECATION")
        fun isSuitableForExternalWifi(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

            val networkInfo = cm.activeNetworkInfo ?: return false

            // 必须是 Wi-Fi 连接
            if (networkInfo.type != ConnectivityManager.TYPE_WIFI) {
                return false
            }

            return networkInfo.isConnected
        }

        /**
         * 获取当前 Wi-Fi 信息。
         */
        @Suppress("DEPRECATION")
        fun getWifiInfo(context: Context): WifiInfoCompat? {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

            val info = wifiManager.connectionInfo ?: return null

            val ssid = info.ssid?.removeSurrounding("\"") ?: return null
            val ip = info.ipAddress
            val linkSpeed = info.linkSpeed
            val frequency = if (ApiLevel.isAtLeast(ApiLevel.LOLLIPOP)) {
                info.frequency
            } else {
                0 // API 17 无法获取频率
            }

            return WifiInfoCompat(
                ssid = ssid,
                ipAddress = ip,
                linkSpeedMbps = linkSpeed,
                frequencyMHz = frequency
            )
        }
    }
}

/**
 * Wi-Fi 信息兼容类。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
data class WifiInfoCompat(
    val ssid: String,
    val ipAddress: Int,
    val linkSpeedMbps: Int,
    val frequencyMHz: Int
) {
    /**
     * 获取点分十进制 IP 地址字符串。
     */
    fun getIpAddressString(): String {
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }

    /**
     * 判断是否为 5GHz 频段。
     */
    fun is5GHz(): Boolean {
        return if (frequencyMHz > 0) {
            frequencyMHz >= 5000
        } else {
            // API 17 无法获取频率，通过 SSID 猜测
            ssid.contains("5G", ignoreCase = true)
        }
    }
}
