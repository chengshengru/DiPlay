package com.shilapi.xcertplay

/**
 * 有线 CarPlay 启动在 Android 17(API 37)起所需的运行时权限。Android 17 上 iPhone 通过 USB 网络的
 * AirPlay 连接也算本地流量:在 emteria 的 Raspberry Pi 5 Android 17 上,iPhone 从其链路本地地址
 * 发往 [fe80::2]:7000 的 SYN 能到达却始终没有回应,直到授予 ACCESS_LOCAL_NETWORK 后才会应答,
 * 因此链路本地 IPv6 不能指望一直豁免(参见 [WirelessPermissions])。
 *
 * android-36 平台 Manifest 无 ACCESS_LOCAL_NETWORK 常量,故使用字面量与权限字符串。
 */
internal object WiredPermissions {
    private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= 37) listOf(PERMISSION_ACCESS_LOCAL_NETWORK) else emptyList()
}
