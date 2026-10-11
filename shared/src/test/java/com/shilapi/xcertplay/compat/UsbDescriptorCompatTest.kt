package com.shilapi.xcertplay.compat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two pure descriptor decoders behind the API 17-20 USB fallback: configuration boundaries in
 * [android.hardware.usb.UsbDevice]'s flat interface list, and the bConfigurationValue of every
 * configuration descriptor read from `UsbDeviceConnection.rawDescriptors`.
 */
class UsbDescriptorCompatTest {

    @Test
    fun interfaceNumberingRestartMarksAConfigurationBoundary() {
        // Config 1 (usbmux, interfaces 0..2), then config 2 where alternate settings repeat 0 and 1.
        assertEquals(
            listOf(0, 3),
            UsbDescriptorCompat.configurationStarts(listOf(0, 1, 2, 0, 0, 1, 2)),
        )
    }

    @Test
    fun repeatedInterfaceNumbersStayInOneConfiguration() {
        // A single-interface configuration with two alternate settings is still one configuration.
        assertEquals(listOf(0), UsbDescriptorCompat.configurationStarts(listOf(0, 0)))
        assertEquals(listOf(0), UsbDescriptorCompat.configurationStarts(listOf(0)))
        assertEquals(emptyList<Int>(), UsbDescriptorCompat.configurationStarts(emptyList()))
    }

    @Test
    fun configurationValuesFollowDescriptorOrder() {
        val raw = byteArrayOf(
            // Configuration descriptor: wTotalLength 0x22, bNumInterfaces 4, bConfigurationValue 4.
            0x09, 0x02, 0x22, 0x00, 0x04, 0x04, 0x00, 0x80.toByte(), 0x32,
            // Interface descriptor, class 0xff subclass 0xfe protocol 0x02.
            0x09, 0x04, 0x00, 0x00, 0x03, 0xff.toByte(), 0xfe.toByte(), 0x02, 0x00,
            // Configuration descriptor: bConfigurationValue 1.
            0x09, 0x02, 0x22, 0x00, 0x05, 0x01, 0x00, 0x80.toByte(), 0x32,
        )
        assertEquals(listOf(4, 1), UsbDescriptorCompat.configurationValues(raw))
    }

    @Test
    fun truncatedOrMissingDescriptorsYieldNoValues() {
        assertEquals(emptyList<Int>(), UsbDescriptorCompat.configurationValues(null))
        assertEquals(emptyList<Int>(), UsbDescriptorCompat.configurationValues(byteArrayOf()))
        // A configuration descriptor that claims more bytes than the buffer holds is not trusted.
        assertEquals(emptyList<Int>(), UsbDescriptorCompat.configurationValues(byteArrayOf(0x09, 0x02, 0x22)))
    }

    @Test
    fun configurationLabelFallsBackToTheOrdinalWhenTheValueIsUnknown() {
        assertEquals("4", UsbConfigurationView(1, 4, emptyList()).label)
        assertEquals("#2", UsbConfigurationView(1, UsbDescriptorCompat.UNKNOWN, emptyList()).label)
    }
}
