package com.shilapi.xcertplay.transport

/**
 * Kernel-level USB configuration control, needed because API 17-20 has no
 * [android.hardware.usb.UsbDeviceConnection.setConfiguration] (added in API 21).
 *
 * A raw SET_CONFIGURATION control transfer switches the iPhone but leaves the Linux kernel's
 * `dev->actconfig` on the previous layout, so a later [android.hardware.usb.UsbDeviceConnection.claimInterface]
 * for an interface of the new configuration fails. The ioctl behind [NativeUsbConfigurationKernel]
 * makes the kernel re-read the configuration and rebuild actconfig, the same effect LIVI gets by
 * writing sysfs `bConfigurationValue`.
 *
 * Both calls return 0 on success and a negated errno otherwise.
 */
internal interface UsbConfigurationKernel {
    /** Selects [configurationValue] on [fileDescriptor]. */
    fun setConfiguration(fileDescriptor: Int, configurationValue: Int): Int

    /** Detaches kernel drivers, because SET_CONFIGURATION is refused with EBUSY while one is bound. */
    fun detachKernelDrivers(fileDescriptor: Int): Int
}

/** Production bridge; degrades to [LIBRARY_UNAVAILABLE] when the shared library cannot be loaded. */
internal object NativeUsbConfigurationKernel : UsbConfigurationKernel {
    private const val LIBRARY_UNAVAILABLE = -1

    private val loaded: Boolean =
        runCatching { System.loadLibrary("xcertplay_usbconfig"); true }.getOrDefault(false)

    override fun setConfiguration(fileDescriptor: Int, configurationValue: Int): Int =
        if (loaded) UsbConfigurationNative.setConfiguration(fileDescriptor, configurationValue)
        else LIBRARY_UNAVAILABLE

    override fun detachKernelDrivers(fileDescriptor: Int): Int =
        if (loaded) UsbConfigurationNative.detachKernelDrivers(fileDescriptor)
        else LIBRARY_UNAVAILABLE
}

private object UsbConfigurationNative {
    external fun setConfiguration(fileDescriptor: Int, configurationValue: Int): Int
    external fun detachKernelDrivers(fileDescriptor: Int): Int
}
