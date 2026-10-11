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

    fun find(device: UsbDevice): UsbConfigurationView? {
        val configurations = UsbDescriptorCompat.configurations(device)
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
    fun describeAll(device: UsbDevice): List<String> = UsbDescriptorCompat.describeAll(device)

    fun usbMuxInterface(configuration: UsbConfigurationView): UsbInterface? =
        configuration.interfaces.firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

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
