package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.compat.ApiLevel

/**
 * Wi-Fi Direct 遗留通道管理 - API 17 兼容版本。
 *
 * API 17 限制：
 * - 没有 `setGroupOperatingFrequency()`（API 29+）；
 * - 没有 `setGroupOperatingBands()`（API 29+）；
 * - 通道控制依赖厂商实现，只能尝试反射调用隐藏 API。
 *
 * API 29+ 直接委托给现有 [WifiP2pLegacyChannels]，避免重复实现。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
internal object WifiP2pLegacyChannelsApi17 {

    // API 17 支持的 2.4GHz 通道（基于 802.11 标准）
    private val SUPPORTED_CHANNELS_2GHZ = listOf(
        1 to 2412,
        2 to 2417,
        3 to 2422,
        4 to 2427,
        5 to 2432,
        6 to 2437,
        7 to 2442,
        8 to 2447,
        9 to 2452,
        10 to 2457,
        11 to 2462,
        12 to 2467,
        13 to 2472
    )

    /**
     * 尝试设置操作通道。
     *
     * API 29+ 委托 [WifiP2pLegacyChannels]；低版本尝试反射调用隐藏 API（可能失败）。
     *
     * @return 是否成功设置
     */
    fun applyChannel(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        targetChannel: Int,
        diagnostic: (String) -> Unit
    ): Boolean {
        if (ApiLevel.isAtLeast(ApiLevel.Q)) {
            // API 29+：使用现代路径
            return WifiP2pLegacyChannels.apply(manager, channel, targetChannel, diagnostic)
        }

        // API 17-28：无法直接设置通道，某些厂商可能支持隐藏 API
        diagnostic("Wi-Fi P2P channel control not available on API ${ApiLevel.current()}")

        return try {
            val method = manager.javaClass.getDeclaredMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java
            )
            method.isAccessible = true

            val frequency = channelToFrequency(targetChannel)
            if (frequency == 0) {
                diagnostic("Unsupported channel: $targetChannel")
                return false
            }

            var success = false
            method.invoke(
                manager,
                channel,
                WifiP2pLegacyChannels.LISTEN_CHANNEL,
                targetChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        success = true
                        diagnostic("Wi-Fi P2P channel set to $targetChannel ($frequency MHz)")
                    }

                    override fun onFailure(reason: Int) {
                        diagnostic("Wi-Fi P2P channel set failed: reason=$reason")
                    }
                }
            )

            success
        } catch (e: Exception) {
            diagnostic("Wi-Fi P2P channel reflection failed: ${e.message}")
            false
        }
    }

    /**
     * 通道号转频率（MHz）。
     */
    private fun channelToFrequency(channel: Int): Int {
        return SUPPORTED_CHANNELS_2GHZ.find { it.first == channel }?.second ?: 0
    }

    /**
     * 获取支持的通道列表。
     */
    fun getSupportedChannels(): List<Int> {
        return SUPPORTED_CHANNELS_2GHZ.map { it.first }
    }

    /**
     * 检查通道是否支持。
     */
    fun isChannelSupported(channel: Int): Boolean {
        return SUPPORTED_CHANNELS_2GHZ.any { it.first == channel }
    }
}
