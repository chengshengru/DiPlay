package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig

/**
 * main #583：DiPlay 经有线 iAP2 向 iPhone 报出的充电电流档位。报价告知 iPhone 最多可取多少电流，
 * 并不限制车机 USB 口的供电能力。当车机 USB 口供不起 iPhone 的充电电流（充电开始数秒后掉出
 * USB 总线再回到默认模式）时，降低报价可能有帮助。
 * 稳定的偏好取值；绝不持久化枚举序号。
 */
enum class UsbChargingCurrent(val key: String, val milliAmps: Int) {
    NORMAL("normal", CarPlayRuntimeConfig.DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS),
    REDUCED("reduced", 1500),
    LOW("low", 500);

    companion object {
        fun fromKey(key: String?): UsbChargingCurrent = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}
