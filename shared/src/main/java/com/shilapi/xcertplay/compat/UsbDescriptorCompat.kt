package com.shilapi.xcertplay.compat

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.Build
import android.util.Log

/**
 * One USB configuration of a device, plus the interfaces (alternate settings included) it carries.
 *
 * [android.hardware.usb.UsbConfiguration] only exists from API 21, so the descriptor code speaks to
 * this view instead and [UsbDescriptorCompat] fills it from whatever the running platform offers.
 */
class UsbConfigurationView internal constructor(
    /** Position in [UsbDevice]'s configuration list, or the descriptor order on API 17-20. */
    val index: Int,
    /** bConfigurationValue, or [UsbDescriptorCompat.UNKNOWN] when the platform does not expose it. */
    val id: Int,
    val interfaces: List<UsbInterface>,
) {
    /** Stable identity for diagnostics: the real value where it is known, the ordinal otherwise. */
    val label: String get() = if (id > 0) id.toString() else "#${index + 1}"
}

/**
 * API 17-safe access to USB configuration, interface and alternate-setting descriptors.
 *
 * minSdk is 17 (Android 4.2.2 Jelly Bean head units) but the transport is written against the
 * modern USB host API. These members are API 21+ and are missing at runtime on API 17-20:
 *
 * - `UsbConfiguration` (the class itself);
 * - `UsbDevice.getConfigurationCount()` / `getConfiguration(int)`;
 * - `UsbInterface.getAlternateSetting()`;
 * - `UsbDeviceConnection.setConfiguration(UsbConfiguration)` / `setInterface(UsbInterface)`.
 *
 * The dalvik verifier only swaps an unresolvable opcode for a throwing stub, so a direct call is a
 * guaranteed [NoSuchMethodError] the moment it runs — see the 0.2.16-api17 `crash-last.log`
 * (`IphoneConfiguration.find` died on `UsbDevice.getConfigurationCount`). Older platforms get their
 * descriptors from [UsbDevice]'s flat interface list and from [UsbDeviceConnection.rawDescriptors],
 * and select configurations and alternate settings with the standard control transfers the
 * framework itself would have sent.
 *
 * Every helper keeps the exact original call where the API exists, so API 21+ behaviour is
 * unchanged; API 17-20 gets a working fallback instead of a crash.
 */
internal object UsbDescriptorCompat {

    /** Value of [UsbConfigurationView.id] when the platform exposes no configuration value. */
    const val UNKNOWN = -1

    /** Value of [alternateSetting] when the platform exposes no alternate setting. */
    const val ALTERNATE_SETTING_UNKNOWN = -1

    private const val TAG = "xcertplay-usb"

    private const val DESCRIPTOR_TYPE_CONFIGURATION = 0x02
    private const val CONFIGURATION_DESCRIPTOR_LENGTH = 9
    private const val CONFIGURATION_VALUE_OFFSET = 5

    private const val REQUEST_TYPE_STANDARD_DEVICE_OUT = 0x00
    private const val REQUEST_TYPE_STANDARD_INTERFACE_OUT = 0x01
    private const val SET_CONFIGURATION_REQUEST = 0x09
    private const val SET_INTERFACE_REQUEST = 0x0b
    private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

    /**
     * Every configuration the device exposes, each with its interfaces and alternate settings.
     *
     * API 21+ enumerates the real configurations. API 17-20 has no `UsbConfiguration`, and the
     * framework's JNI hands every interface descriptor of the whole device to the app as one flat
     * list (`UsbDevice.getInterfaceCount()`), so the list is cut back into configurations here.
     */
    fun configurations(device: UsbDevice): List<UsbConfigurationView> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) lollipopConfigurations(device)
        else legacyConfigurations(device)

    /**
     * [UsbDeviceConnection.setConfiguration] is API 21+. API 17-20 sends the standard
     * SET_CONFIGURATION request instead, using the bConfigurationValue read back from the device's
     * raw descriptors (the framework exposes no configuration value there).
     */
    fun selectConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configuration: UsbConfigurationView,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (configuration.index !in 0 until device.configurationCount) return false
            return connection.setConfiguration(device.getConfiguration(configuration.index))
        }
        val value = configuration.id.takeIf { it > 0 }
            ?: configurationValues(connection.rawDescriptors).getOrNull(configuration.index)
            ?: return false
        val transferred = connection.controlTransfer(
            REQUEST_TYPE_STANDARD_DEVICE_OUT,
            SET_CONFIGURATION_REQUEST,
            value,
            0,
            null,
            0,
            CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        Log.i(TAG, "legacy SET_CONFIGURATION value=$value index=${configuration.index} read=$transferred")
        return transferred >= 0
    }

    /**
     * [UsbDeviceConnection.setInterface] is API 21+. API 17-20 sends the standard SET_INTERFACE
     * request instead, which is what selects the alternate setting that carries bulk endpoints.
     */
    fun selectInterface(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        alternateSetting: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return connection.setInterface(usbInterface)
        val transferred = connection.controlTransfer(
            REQUEST_TYPE_STANDARD_INTERFACE_OUT,
            SET_INTERFACE_REQUEST,
            alternateSetting.coerceAtLeast(0),
            usbInterface.id,
            null,
            0,
            CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        Log.i(
            TAG,
            "legacy SET_INTERFACE iface=${usbInterface.id} alt=$alternateSetting read=$transferred",
        )
        return transferred >= 0
    }

    /**
     * [UsbInterface.getAlternateSetting] is API 21+. API 17-20 does not carry the field at all
     * (its constructor takes no alternate setting), so callers get an explicit "unknown" instead
     * of a wrong zero; only tie-breaking and diagnostics depend on it.
     */
    fun alternateSetting(usbInterface: UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) usbInterface.alternateSetting
        else ALTERNATE_SETTING_UNKNOWN

    /**
     * [UsbDevice.getVersion] is API 21+. API 17-20 has no device BCD version getter, so callers get
     * `null` and must treat it as "unknown" (diagnostics only; nothing functional depends on it).
     */
    fun deviceVersion(device: UsbDevice): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.version else null

    /** `interfaceId/alternateSetting:class.subclass.protocolxEndpoints` per interface. */
    fun describe(configuration: UsbConfigurationView): String =
        configuration.interfaces.joinToString(",") { usbInterface ->
            val alternate = alternateSetting(usbInterface)
            val alternateLabel = if (alternate == ALTERNATE_SETTING_UNKNOWN) "?" else alternate.toString()
            "${usbInterface.id}/$alternateLabel" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    /** Every configuration of the device with its descriptor summary, for USB diagnostics. */
    fun describeAll(device: UsbDevice): List<String> =
        configurations(device).map { "${it.label}[${describe(it)}]" }

    private fun lollipopConfigurations(device: UsbDevice): List<UsbConfigurationView> {
        val count = device.configurationCount
        val configurations = ArrayList<UsbConfigurationView>(count)
        for (index in 0 until count) {
            val configuration = device.getConfiguration(index)
            val interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface)
            configurations.add(UsbConfigurationView(index, configuration.id, interfaces))
        }
        return configurations
    }

    private fun legacyConfigurations(device: UsbDevice): List<UsbConfigurationView> {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val ids = interfaces.map { it.id }
        val starts = configurationStarts(ids)
        return starts.mapIndexed { ordinal, start ->
            val end = starts.getOrElse(ordinal + 1) { interfaces.size }
            UsbConfigurationView(ordinal, UNKNOWN, interfaces.subList(start, end).toList())
        }
    }

    /**
     * Indices where a configuration starts in a flat interface list.
     *
     * Alternate settings repeat their interface number and appear in ascending order, while the next
     * configuration restarts the numbering at 0, so a decrease marks a new configuration. An empty
     * list yields no configuration rather than a bogus one.
     */
    internal fun configurationStarts(interfaceIds: List<Int>): List<Int> {
        val starts = ArrayList<Int>()
        for (index in interfaceIds.indices) {
            if (starts.isEmpty() || interfaceIds[index] < interfaceIds[index - 1]) starts.add(index)
        }
        return starts
    }

    /**
     * bConfigurationValue of every configuration descriptor, in descriptor order.
     *
     * Only used on API 17-20, where the configuration value is unavailable from the framework.
     */
    internal fun configurationValues(rawDescriptors: ByteArray?): List<Int> {
        val raw = rawDescriptors ?: return emptyList()
        val values = ArrayList<Int>()
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return values
            if (type == DESCRIPTOR_TYPE_CONFIGURATION && length >= CONFIGURATION_DESCRIPTOR_LENGTH) {
                values.add(raw[offset + CONFIGURATION_VALUE_OFFSET].toInt() and 0xff)
            }
            offset += length
        }
        return values
    }
}
