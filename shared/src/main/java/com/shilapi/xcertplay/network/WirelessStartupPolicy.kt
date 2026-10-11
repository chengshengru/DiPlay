package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** 车机的 Android 蓝牙接受了根本到不了手机的连接(main #590)。 */
    BLUETOOTH_UNUSABLE;

    /** false 表示再多次尝试也无法改变失败原因。 */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != BLUETOOTH_UNUSABLE
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
