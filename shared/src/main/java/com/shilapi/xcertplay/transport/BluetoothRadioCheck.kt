package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.IOException

/**
 * 移植自 main #677（波次十一批次A）：无线启动前，车机蓝牙无线电可用性的前置判断。
 *
 * 无线 CarPlay 经蓝牙开始握手。无线电关闭时，先把车机热点或 Wi-Fi Direct 组起起来只是为一次
 * 注定失败的尝试做无用功；在单射频车机上，这还可能把车机从原本使用的 Wi-Fi 上带离，而每次重试
 * 都会再发生一次。因此这里在**改动 Wi-Fi 之前**先判定无线电是否可用。
 *
 * 自己（DiPlay）暂停的无线电会在握手前恢复，因此不在此处拦截。
 *
 * API 17：本对象为纯逻辑，仅依赖字符串常量与布尔值，无任何高版本 API 依赖。
 */
object BluetoothRadioCheck {
    /**
     * 为什么这次尝试无法触达 iPhone；可以继续时返回 null。
     * [pausedByDiPlay] 为真的无线电会在握手前被恢复，因此不作为阻断原因。
     */
    fun blocker(adapterPresent: Boolean, enabled: Boolean, pausedByDiPlay: Boolean): String? = when {
        !adapterPresent -> NO_ADAPTER
        !enabled && !pausedByDiPlay -> RADIO_OFF
        else -> null
    }

    /**
     * main #799（波次十三批次E）：把阻断原因转成要抛出的异常。无线电关闭是可以再打开的，
     * 因此该尝试仍会重试；而重试并不能给车机变出一个蓝牙适配器，所以无适配器时直接带上原因终止。
     */
    fun failure(blocker: String): IOException =
        if (blocker == NO_ADAPTER) WirelessStartupException(WirelessStartupFailure.BLUETOOTH_UNUSABLE, blocker)
        else IOException(blocker)

    /** 以宿主屏匹配"应用无法使用蓝牙"关键词的措辞结尾。 */
    const val NO_ADAPTER = "Bluetooth adapter is unavailable: Android on this head unit ${BluetoothLinkCheck.NO_SOCKETS_MARK}"

    /** 要求驾驶员去操作的措辞；宿主屏按此关键词匹配本地化提示。 */
    const val RADIO_OFF_MARK = "Turn on Bluetooth"

    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
