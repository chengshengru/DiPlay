package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
    /** Opt-in legacy AP recovery (API 17..22): one bounded AP restart when the interface never appears. */
    private val legacyRecoveryEnabled: Boolean = false,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val interfaces = ManualHotspotInterfaces(appContext, onDiagnostic)
    private val waitLock = Object()
    private var confirmed: HotspotSelection? = null
    private var lastSampleLog = emptyList<String>()
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val readConfiguration = readApConfiguration()
        // 波次十 #617：车机会同时广播 HOTSPOT 与 HOTSPOT_5G（5 GHz companion），Android 只
        // 认识前一个，这里识别后缀并放行，避免名称不匹配直接失败。
        val companion = readConfiguration != null &&
            isFiveGhzCompanion(apSsid = readConfiguration.ssid, expectedSsid = expectedSsid)
        if (readConfiguration != null && readConfiguration.ssid != expectedSsid && !companion) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${readConfiguration.ssid}'",
            )
        }
        // Android 配置描述的是 2.4 GHz 网络，其信道/频段/频率对 companion 无意义，只沿用安全设置。
        val apConfiguration = if (companion) null else readConfiguration
        if (companion) onDiagnostic("Manual hotspot uses the car's 5 GHz companion network of the configured hotspot")
        validateApConfiguration(apConfiguration)

        // 老安卓（API 17..22）：已启用的 AP 若长时间没有接口，做一次有界重启恢复。
        // 开关默认关闭；isInterfaceReady 复用与主循环一致的选择逻辑，避免误判。
        val recovery = LegacyHotspotRecovery(
            api = Build.VERSION.SDK_INT,
            enabled = legacyRecoveryEnabled,
            expectedSsid = expectedSsid,
            port = LegacyHotspotRecovery.AndroidPort(wifiManager),
            onDiagnostic = onDiagnostic,
            isInterfaceReady = { selectHotspotInterface(interfaces.sample(), {}) != null },
        )
        val recoveryDeadline = SystemClock.elapsedRealtime() + timeoutMillis

        // 只在候选证据变化时记录，避免每 250ms 重复输出。
        val selected = ManualHotspotReadiness(
            sample = {
                interfaces.sample().also { snapshot ->
                    val messages = mutableListOf<String>()
                    selectHotspotInterface(snapshot, messages::add)
                    if (messages != lastSampleLog) {
                        messages.forEach(onDiagnostic)
                        lastSampleLog = messages
                    }
                }
            },
            cancelled = { closed || isCancelled() },
            pause = { millis -> synchronized(waitLock) { if (!closed && !isCancelled()) waitLock.wait(millis) } },
            log = {},
        ).await(timeoutMillis) { interfaceReady ->
            recovery.recoverIfNeeded(interfaceReady, recoveryDeadline) { closed || isCancelled() }
        }
        confirmed = selected
        onDiagnostic("hotspot interface confirmed iface=${selected.name} index=${selected.index} atNs=${System.nanoTime()}")
        val network = NetworkInterface.getByName(selected.name)
        val localInterface = LocalHotspotInterface(selected.name, selected.address,
            runCatching { network?.hardwareAddress?.toMacAddressString() }.getOrNull()
                ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                ?: HotspotInterfaceBssid.read(selected.name))
        val connectionFrequency = if (companion) null else frequencyFromConnectionInfo()
        val scanFrequency = if (companion) null else frequencyFromScanResult(localInterface)
        val channel = observedManualHotspotChannel(
            apChannel = apConfiguration?.channel ?: 0,
            connectionFrequencyMHz = connectionFrequency,
            scanFrequencyMHz = scanFrequency,
            apFrequencyMHz = apConfiguration?.frequencyMHz,
        )
        val frequencyMHz = when {
            apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
            connectionFrequency != null -> connectionFrequency
            scanFrequency != null -> scanFrequency
            else -> null
        }
        val security = readConfiguration?.security ?: expectedSecurity
        // Keep the existing IPv4-first endpoint selection and also publish the selected AP's
        // scoped IPv6 address, using the same dual-stack policy as existing Wi-Fi connections.
        val hostAddresses = network?.let {
            existingWifiHostAddresses(Collections.list(it.inetAddresses), selected.index)
        }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(localInterface.hostAddress)
        onDiagnostic("Manual hotspot configReadable=${readConfiguration != null} " +
            "security=$security channelKnown=${channel > 0} " +
            "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
            "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
            "mdnsFamilies=${hostAddresses.joinToString("+") { address -> if (address is Inet6Address) "IPv6" else "IPv4" }}")
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is secured but no passphrase was provided")
        }

        if (channel == 0) {
            Log.w(
                TAG,
                "Could not read the active hotspot channel from Android public APIs; " +
                    "reporting iAP2 channel 0 (auto) instead of configured channel " +
                    "$expectedChannel",
            )
        }
        val observedBandLabel = wifiBandLabel(apConfiguration?.band)
        return WirelessHotspotInfo(
            ssid = expectedSsid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = localInterface.hardwareAddress,
            interfaceName = localInterface.name,
            hostAddress = localInterface.hostAddress,
            hostAddresses = hostAddresses,
            bandLabel = when (expectedBand) {
                ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                ManualHotspotBand.GHZ_5 -> "5 GHz"
                ManualHotspotBand.AUTO -> if (companion) "5 GHz" else
                    frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
            },
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
        )
    }

    override fun validateReady() {
        val expected = confirmed ?: throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
        )
        val current = selectHotspotInterface(interfaces.sample(), onDiagnostic)
        if (closed || isCancelled() || current == null || !expected.sameAddress(current)) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Hotspot interface or address changed before publication")
        }
    }

    override fun close() {
        closed = true
        synchronized(waitLock) { waitLock.notifyAll() }
        interfaces.close()
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            // ReflectiveOperationException 是 API 19+ 类，API 17 验证器会整类拒绝，改用具体异常。
            } catch (_: NoSuchFieldException) {
                0
            } catch (_: IllegalAccessException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: NoSuchFieldException) {
                null
            } catch (_: IllegalAccessException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
    )

    internal companion object {
        private const val TAG = "xcertplay-usb"
        private val FIVE_GHZ_SUFFIXES = listOf("_5G", "-5G")

        /**
         * 波次十 #617：部分车机在 Android 里配置一个热点名，同时以 “_5G” 后缀（大小写不敏感）
         * 额外广播同名 5 GHz 网络、密码相同。Android 只认识前者，选后者会按名称不匹配失败。
         */
        fun isFiveGhzCompanion(apSsid: String, expectedSsid: String): Boolean =
            apSsid.isNotEmpty() && FIVE_GHZ_SUFFIXES.any { expectedSsid.equals(apSsid + it, ignoreCase = true) }
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
