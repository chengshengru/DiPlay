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
    private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
    private const val INTERFACE_DESCRIPTOR_LENGTH = 9
    private const val INTERFACE_NUMBER_OFFSET = 2

    private const val REQUEST_TYPE_STANDARD_DEVICE_OUT = 0x00
    private const val REQUEST_TYPE_STANDARD_INTERFACE_OUT = 0x01
    private const val SET_CONFIGURATION_REQUEST = 0x09
    private const val SET_INTERFACE_REQUEST = 0x0b
    private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

    /** One configuration of the raw descriptor chain: its bConfigurationValue and interface numbers. */
    internal data class DescriptorConfiguration(val value: Int, val interfaceIds: List<Int>)

    /**
     * Every configuration the device exposes, each with its interfaces and alternate settings.
     *
     * API 21+ enumerates the real configurations. API 17-20 has no `UsbConfiguration`, and the
     * framework's JNI hands every interface descriptor of the whole device to the app as one flat
     * list (`UsbDevice.getInterfaceCount()`), so the list is cut back into configurations here.
     *
     * @param rawDescriptors `UsbDeviceConnection.getRawDescriptors()` when the caller holds an open
     *   connection. With it the split is exact and carries the real configuration values; without
     *   it the split falls back to the interface-number heuristic and [UsbConfigurationView.id]
     *   stays [UNKNOWN].
     */
    fun configurations(device: UsbDevice, rawDescriptors: ByteArray? = null): List<UsbConfigurationView> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) lollipopConfigurations(device)
        else legacyConfigurations(device, rawDescriptors)

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
    fun describeAll(device: UsbDevice, rawDescriptors: ByteArray? = null): List<String> =
        configurations(device, rawDescriptors).map { "${it.label}[${describe(it)}]" }

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

    /**
     * API 17-20 的配置切分：优先用 raw descriptors（配置值 + 每个配置的接口号顺序都可读），
     * 拿不到或与框架的平铺接口列表不一致时，回退到"接口号回落"启发式并放弃配置值。
     *
     * 只靠接口号回落会漏切：接口号在配置之间重新从 0 开始，**单接口配置**（接口号只有 0）接在
     * 上一个以 0 结尾的配置后面时不会回落，两个配置会被并成一个；而配置值只能按序号去猜
     * （`configurationValues` 的第 N 项）。真机上因此把 iPhone 切到了错误的配置值（5，而非
     * LIVI 的 CarPlay 配置 6），随后 claim 的接口在该配置里并不存在，`claimInterface` 失败。
     */
    private fun legacyConfigurations(device: UsbDevice, rawDescriptors: ByteArray?): List<UsbConfigurationView> {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val parsed = descriptorConfigurations(rawDescriptors)
        if (parsed.isNotEmpty() && parsed.sumOf { it.interfaceIds.size } == interfaces.size) {
            var cursor = 0
            val configurations = ArrayList<UsbConfigurationView>(parsed.size)
            var consistent = true
            parsed.forEachIndexed { ordinal, configuration ->
                val slice = interfaces.subList(cursor, cursor + configuration.interfaceIds.size).toList()
                cursor += configuration.interfaceIds.size
                if (slice.map { it.id } != configuration.interfaceIds) consistent = false
                configurations.add(UsbConfigurationView(ordinal, configuration.value, slice))
            }
            if (consistent) return configurations
        }
        val ids = interfaces.map { it.id }
        val starts = configurationStarts(ids)
        return starts.mapIndexed { ordinal, start ->
            val end = starts.getOrElse(ordinal + 1) { interfaces.size }
            UsbConfigurationView(ordinal, UNKNOWN, interfaces.subList(start, end).toList())
        }
    }

    /**
     * 按描述符顺序解析配置骨架：每个配置的 bConfigurationValue 与接口号（含备选设置，
     * 与框架平铺列表一一对应）。不认识的描述符按自身长度跳过；长度非法或越界即整体放弃
     * （宁可回退到启发式，也不给出半截结果）。
     */
    internal fun descriptorConfigurations(rawDescriptors: ByteArray?): List<DescriptorConfiguration> {
        val raw = rawDescriptors ?: return emptyList()
        val configurations = ArrayList<DescriptorConfiguration>()
        var interfaceIds: MutableList<Int>? = null
        var value = 0
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return emptyList()
            when {
                type == DESCRIPTOR_TYPE_CONFIGURATION && length >= CONFIGURATION_DESCRIPTOR_LENGTH -> {
                    interfaceIds?.let { configurations.add(DescriptorConfiguration(value, it)) }
                    value = raw[offset + CONFIGURATION_VALUE_OFFSET].toInt() and 0xff
                    interfaceIds = ArrayList()
                }
                type == DESCRIPTOR_TYPE_INTERFACE && length >= INTERFACE_DESCRIPTOR_LENGTH -> {
                    interfaceIds?.add(raw[offset + INTERFACE_NUMBER_OFFSET].toInt() and 0xff)
                }
            }
            offset += length
        }
        interfaceIds?.let { configurations.add(DescriptorConfiguration(value, it)) }
        return configurations
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
