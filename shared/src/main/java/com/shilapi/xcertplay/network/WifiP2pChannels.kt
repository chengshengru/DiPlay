package com.shilapi.xcertplay.network

/** 一个 Wi-Fi Direct 频段选择：车机可以在 [frequenciesMHz] 范围内自选信道，但不得离开该频段（main #432）。 */
enum class WifiP2pBand(val label: String, val frequenciesMHz: IntRange) {
    TWO_GHZ("2.4 GHz", 2412..2484),
    FIVE_GHZ("5 GHz", 5150..5895);

    fun contains(frequencyMHz: Int?): Boolean = frequencyMHz != null && frequencyMHz in frequenciesMHz
}

/** Non-DFS channel choices. Android still enforces the radio's capabilities and country limits. */
object WifiP2pChannels {
    const val AUTO = 0
    /** Auto 限定单个频段。负值不会与真实信道号冲突。 */
    const val AUTO_5_GHZ = -5
    const val AUTO_2_4_GHZ = -2
    val bandChoices: List<Int> = listOf(AUTO_5_GHZ, AUTO_2_4_GHZ)
    val channels: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + (1..11)

    fun isValid(channel: Int): Boolean = channel == AUTO || channel in bandChoices || channel in channels

    /** "Auto · 频段" 选择被限制的频段；Auto 与手动信道为 null。 */
    fun band(channel: Int): WifiP2pBand? = when (channel) {
        AUTO_5_GHZ -> WifiP2pBand.FIVE_GHZ
        AUTO_2_4_GHZ -> WifiP2pBand.TWO_GHZ
        else -> null
    }

    fun frequencyMhz(channel: Int): Int? {
        require(isValid(channel)) { "Unsupported Wi-Fi Direct channel: $channel" }
        return when {
            channel == AUTO || channel in bandChoices -> null
            channel <= 11 -> 2407 + channel * 5
            else -> 5000 + channel * 5
        }
    }

    /** 诊断与错误文案；不做本地化。 */
    fun describe(channel: Int): String = when {
        channel == AUTO -> "auto"
        band(channel) != null -> "auto-${requireNotNull(band(channel)).label.replace(" ", "")}"
        else -> channel.toString()
    }
}

internal class P2pChannelUnavailableException(channel: Int, details: String, cause: Throwable? = null) :
    java.io.IOException(
        WifiP2pChannels.band(channel)?.let { "Wi-Fi Direct could not use the ${it.label} band. Choose Auto or another channel. $details" }
            ?: "Wi-Fi Direct could not use channel $channel. Choose Auto or another channel. $details",
        cause,
    )
