package com.shilapi.xcertplay.transport

/**
 * 选择"用哪种方式切 USB 配置"。纯逻辑(依赖注入),便于单测。
 *
 * API 17-20 的顺序是:优先内核级 `USBDEVFS_SETCONFIGURATION` ioctl(会刷新 `dev->actconfig`,
 * 之后 `claimInterface` 才能看到新配置的接口);ioctl 失败或拿不到文件描述符时,退回原始
 * SET_CONFIGURATION 控制传输——后者只切设备、不刷新内核,是"iPhone 切到了 6、内核仍认为是旧配置"
 * 那个卡点的来源,只作为兜底保留。
 *
 * 内核路径每次都会先尝试 [kernelDetach](USBDEVFS_DISCONNECT),因为任一接口仍绑定驱动时
 * SET_CONFIGURATION 会被内核以 EBUSY 拒绝。
 */
internal object UsbConfigurationSwitch {
    data class Outcome(
        val usedKernel: Boolean,
        val selected: Boolean,
        /** 内核路径尝试过的 detach 返回值;仅内核路径非 null。 */
        val kernelDetach: Int?,
        /** 内核 ioctl 返回值;仅内核路径非 null。 */
        val kernelResult: Int?,
        /** 控制传输返回值;仅在控制传输被调用时非 null。 */
        val controlResult: Boolean?,
    ) {
        fun diagnostic(target: Int, fileDescriptor: Int, active: Int?): String =
            if (usedKernel) {
                "usb config kernel SET_CONFIGURATION($target) fd=$fileDescriptor " +
                    "detach=${kernelDetach ?: "n/a"} result=${kernelResult ?: "n/a"} " +
                    "controlFallback=${controlResult ?: "skipped"} active=${active ?: "unknown"} ok=$selected"
            } else {
                "usb config control SET_CONFIGURATION($target) fd=$fileDescriptor " +
                    "ok=${controlResult ?: false} active=${active ?: "unknown"}"
            }
    }

    /**
     * 文件描述符无效(未打开/取不到)时直接走控制传输;否则先 [kernelDetach] 再 [kernelSet],
     * 仅当 ioctl 返回 0 才算成功,否则回退 [controlSet]。
     */
    fun switch(
        fileDescriptor: Int,
        target: Int,
        kernelDetach: (Int) -> Int,
        kernelSet: (Int, Int) -> Int,
        controlSet: () -> Boolean,
    ): Outcome {
        if (fileDescriptor < 0) {
            val control = controlSet()
            return Outcome(
                usedKernel = false,
                selected = control,
                kernelDetach = null,
                kernelResult = null,
                controlResult = control,
            )
        }
        val detach = kernelDetach(fileDescriptor)
        val result = kernelSet(fileDescriptor, target)
        if (result == 0) {
            return Outcome(
                usedKernel = true,
                selected = true,
                kernelDetach = detach,
                kernelResult = result,
                controlResult = null,
            )
        }
        val control = controlSet()
        return Outcome(
            usedKernel = true,
            selected = control,
            kernelDetach = detach,
            kernelResult = result,
            controlResult = control,
        )
    }
}
