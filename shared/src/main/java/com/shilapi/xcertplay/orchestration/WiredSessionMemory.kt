package com.shilapi.xcertplay.orchestration

/**
 * DiPlay 上一次在此 iPhone 上启动有线 CarPlay 会话时的 USB 枚举节点(设备节点名)。
 *
 * 当 DiPlay 自己结束该会话(如 Save 后重连)时,iPhone 仍在同一个节点上保持 CarPlay 模式。
 * 该节点上的新会话会通过 iAP2 与 StartSession,但 iPhone 在 USB 重新枚举(30–150s 后)之前
 * 不会再打开 AirPlay 连接;而一次新的 CarPlay 模式请求反而会在数秒内连通(#335)。
 * 只要 iPhone 重新枚举,节点名就会变化,因此节点名一致即说明它仍处于该会话遗留的模式。
 */
internal object WiredSessionMemory {
    @Volatile private var startedOn: String? = null

    fun started(deviceName: String) {
        startedOn = deviceName
    }

    fun startedOn(deviceName: String): Boolean = startedOn == deviceName

    internal fun clear() {
        startedOn = null
    }
}
