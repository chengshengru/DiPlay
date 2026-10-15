package com.shilapi.xcertplay

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * 无线 CarPlay 启动在各 Android 版本下需要的运行时权限（移植自 main #465）。
 *
 * Android 17（API 37）起，target 37 的应用在未授予 ACCESS_LOCAL_NETWORK 时本地 IPv4 流量会被
 * 静默丢弃（链路本地 IPv6 仍可用），无线连接会卡在准备页而非明确失败。
 *
 * API 17 适配：本分支 targetSdk=17，Android 17 不会对低 target 应用强制执行该权限；
 * 且 android-36 编译平台缺少 `Build.VERSION_CODES.CINNAMON_BUN` 与
 * `Manifest.permission.ACCESS_LOCAL_NETWORK` 常量，故使用字面量 37 与权限字符串。
 * 在 API 17 设备上 sdkInt 远低于 37，整个分支为 no-op。
 */
internal object WirelessPermissions {
    /** Android 17 的 API 级别（android-36 平台无 CINNAMON_BUN 常量，使用字面量）。 */
    private const val API_LEVEL_LOCAL_NETWORK = 37

    /** ACCESS_LOCAL_NETWORK 权限名（android-36 平台 Manifest 无此常量）。 */
    private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun required(hotspotMode: WirelessHotspotMode, sdkInt: Int): List<String> = when {
        hotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }.let { permissions ->
        if (sdkInt >= API_LEVEL_LOCAL_NETWORK) {
            permissions + PERMISSION_ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }
    }

    /**
     * What the CarPlay screen asks for once the wireless start permissions were refused (#290): Location,
     * or Nearby devices for Bluetooth and Nearby Wi-Fi devices. Anything else, such as Android 17's local
     * network access, is not under Nearby devices, so the screen only says to check the app permissions.
     */
    @StringRes
    fun refusalMessage(missing: Collection<String>): Int = when {
        Manifest.permission.ACCESS_FINE_LOCATION in missing || Manifest.permission.ACCESS_COARSE_LOCATION in missing ->
            R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        Manifest.permission.BLUETOOTH_CONNECT in missing || Manifest.permission.NEARBY_WIFI_DEVICES in missing ->
            R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        else -> R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
    }
}
