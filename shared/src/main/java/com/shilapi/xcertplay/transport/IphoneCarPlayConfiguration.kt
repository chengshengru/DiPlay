package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.shilapi.xcertplay.compat.UsbConfigurationView
import com.shilapi.xcertplay.compat.UsbDescriptorCompat

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 *
 * Configuration descriptors themselves are API 21+ ([UsbDescriptorCompat] owns the API 17-20
 * fallback), so this object only ever reads interfaces through a [UsbConfigurationView].
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    fun find(device: UsbDevice, rawDescriptors: ByteArray? = null): UsbConfigurationView? {
        val configurations = UsbDescriptorCompat.configurations(device, rawDescriptors)
        val chosen = configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) }
        Log.i(
            TAG,
            "carplay config chosen=${chosen?.label} " +
                "available=${configurations.map { it.label }} " +
                "detail=${chosen?.let(UsbDescriptorCompat::describe)}",
        )
        return chosen
    }

    /** Every configuration of the device with its descriptor summary, for USB diagnostics. */
    fun describeAll(device: UsbDevice, rawDescriptors: ByteArray? = null): List<String> =
        UsbDescriptorCompat.describeAll(device, rawDescriptors)

    /**
     * 波次十 #634：只有 CarPlay 模式才带 Apple Ethernet；iOS 17+ 在非 CarPlay 模式下也会
     * 提供 USBMUX 与 NCM。参数用分支的 [UsbConfigurationView] 抽象（API 17-20 走描述符回退）。
     */
    fun isCarPlayMode(configuration: UsbConfigurationView): Boolean =
        usbMuxInterface(configuration) != null && hasCdcNcm(configuration) && hasAppleEthernet(configuration)

    fun usbMuxInterface(configuration: UsbConfigurationView): UsbInterface? =
        configuration.interfaces.firstOrNull(::isUsbMuxInterface)

    /**
     * 设备上全部 USBMUX 同族接口（跨配置），[configuration] 里的那个排在最前，按 `接口号:端点数`
     * 去重。
     *
     * API 17-20 切配置只能靠原始 SET_CONFIGURATION 控制传输，内核 actconfig 不一定跟着刷新，
     * 于是"描述符里属于 CarPlay 配置的接口号"可能不是内核当前认识的接口号。逐个候选尝试，
     * 让真正处于活动配置里的那个接口被选中。
     */
    fun usbMuxCandidates(device: UsbDevice, configuration: UsbConfigurationView): List<UsbInterface> {
        val preferred = usbMuxInterface(configuration)
        val everyInterface = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter(::isUsbMuxInterface)
        return (listOfNotNull(preferred) + everyInterface).distinctBy { it.id to it.endpointCount }
    }

    private fun isUsbMuxInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == USBMUX_CLASS &&
            usbInterface.interfaceSubclass == USBMUX_SUBCLASS &&
            usbInterface.interfaceProtocol == USBMUX_PROTOCOL

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun hasCdcNcm(configuration: UsbConfigurationView): Boolean =
        configuration.interfaces.any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: UsbConfigurationView): Boolean =
        configuration.interfaces.any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
