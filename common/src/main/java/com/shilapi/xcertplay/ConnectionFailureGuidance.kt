package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.Locale

/**
 * 移植自 main #673（波次十一批次B）：把一次连接失败映射为**建议用户执行的操作**，
 * 而不是从任意异常消息里推断出的"诊断"。
 *
 * 只按可识别的关键词与 [WirelessStartupFailure] 归类；无法归类时给通用建议。
 */
internal object ConnectionFailureGuidance {
    @StringRes
    fun message(
        failure: CarPlayStatus.Failed,
        wireless: Boolean,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    ): Int {
        if (failure.wifiResetRequired) return R.string.connection_help_reset_wifi
        when (failure.startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return network(mode)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return iphoneWifi(mode)
            null -> Unit
        }

        val error = failure.message.lowercase(Locale.ROOT)
        // Android 自身拒绝的权限（如 MIUI 13，main #671）不是驾驶员能授予的。
        if ("securityexception" in error || "lacks permission" in error) return R.string.connection_help_general
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error || "creategroup" in error) {
            return network(mode)
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return iphoneWifi(mode)
        }
        return R.string.connection_help_general
    }

    /** iPhone 要接入的网络：车机热点、Wi-Fi Direct 组，或共享 Wi-Fi。 */
    @StringRes
    private fun network(mode: WirelessHotspotMode): Int = when (mode) {
        WirelessHotspotMode.MANUAL -> R.string.connection_help_hotspot
        WirelessHotspotMode.EXISTING_WIFI -> R.string.connection_help_existing_wifi
        WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.connection_help_wifi_direct
    }

    /** 只有车机热点是驾驶员在 iPhone 上要加入的网络。 */
    @StringRes
    private fun iphoneWifi(mode: WirelessHotspotMode): Int =
        if (mode == WirelessHotspotMode.MANUAL) R.string.connection_help_iphone_wifi else network(mode)
}
