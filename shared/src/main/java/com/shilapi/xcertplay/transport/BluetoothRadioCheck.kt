package com.shilapi.xcertplay.transport

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

    const val NO_ADAPTER = "Bluetooth adapter is unavailable"

    /** 要求驾驶员去操作的措辞；宿主屏按此关键词匹配本地化提示。 */
    const val RADIO_OFF_MARK = "Turn on Bluetooth"

    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
