package com.shilapi.xcertplay.transport

/**
 * 车机内核驱动占用当前配置时,把 iPhone 切到 CarPlay 配置(移植自 main #618,#518)。
 *
 * 任何接口被内核驱动绑定时,Linux 会拒绝 SET_CONFIGURATION
 * ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6")。
 * 8227L 车机内核曾把 snd-usb-audio 绑到 iPhone 的音频配置上,导致每次切换都失败、
 * claim 落到错误配置上,DiPlay 报"无法 claim NCM 接口 3"。
 * Android 只能通过强制 claim 分离内核驱动:对每个接口号强制 claim(保持持有)。
 * 0.2.19 中 releaseInterface() 会让内核重新绑定驱动(USBDEVFS_CONNECT),在切换前撤销了
 * detach,因此改为保持 claim、由 [reopen] 关闭连接丢弃这些 claim——usbfs 在 close 时释放
 * 且不重新绑定驱动。
 *
 * 本对象为纯逻辑(依赖注入),API 17 可直接使用;调用侧由 IphoneUsbHost 接线。
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        /** Android 报告切换失败,且 iPhone 仍处于另一配置。 */
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}"
    }

    /**
     * 首次 [setConfiguration] 成功时返回 null。否则(若 [activeConfiguration] 尚未读到
     * [target])对 [interfaceIds] 逐个执行 [detach] 分离驱动(claim 保持持有),让 [reopen]
     * 关闭并重新打开连接以丢弃这些 claim,再在新连接上重试一次。
     */
    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
        reopen: () -> Unit,
    ): Outcome? {
        if (setConfiguration()) return null
        val before = activeConfiguration()
        if (before == target) return Outcome(target, selected = false, before, before, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        if (detached.isNotEmpty()) reopen()
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}
