package com.shilapi.xcertplay.orchestration

/**
 * 波次十 #634：USB 权限授予后，对有线 iPhone 提供的各配置应采取的动作。
 *
 * iOS 17 及以上在非 CarPlay 模式也会提供 USBMUX 与 NCM：一台 iOS 26 的 iPhone 12 Pro Max
 * 列出 5 个配置，第 5 个两者皆有却没有 Apple Ethernet 接口。此前直接采用它：iAP2 与
 * StartSession 都成功，但 iPhone 始终不打开 AirPlay。发出一次 CarPlay 模式请求后，同一台
 * 手机以配置 6 重新枚举并恢复正常。因此没有 Apple Ethernet 的配置只在模式请求尝试过之后
 * 才允许采用。
 */
internal enum class WiredConfigurationAction(val diagnostic: String) {
    USE("reuse-descriptors"),
    USE_WITHOUT_APPLE_ETHERNET("reuse-without-apple-ethernet"),
    REQUEST_TRANSITION("request-transition"),
    REJECT("reject-missing-configuration"),
    ;

    companion object {
        /**
         * [found] 表示该配置同时具备 USBMUX 与 NCM，[carPlayMode] 表示它还带 Apple Ethernet。
         * 被拒绝的请求回落到 [fallbackAfterRejection]。
         */
        fun choose(found: Boolean, carPlayMode: Boolean, transitionsTried: Int, maximumTransitions: Int) = when {
            found && carPlayMode -> USE
            found && transitionsTried > 0 -> USE_WITHOUT_APPLE_ETHERNET
            transitionsTried < maximumTransitions -> REQUEST_TRANSITION
            else -> REJECT
        }

        /**
         * 拒绝模式请求的 iPhone（iOS 12、iOS 15、锁屏状态等）仍按原样在它提供的配置上尝试。
         */
        fun fallbackAfterRejection(found: Boolean, carPlayMode: Boolean): Boolean = found && !carPlayMode
    }
}
