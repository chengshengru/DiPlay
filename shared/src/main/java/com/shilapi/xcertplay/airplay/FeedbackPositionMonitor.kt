package com.shilapi.xcertplay.airplay

/**
 * 比较 /feedback 上报的播放位置与最新收到的 RTP 采样(main #625b,#620)。
 *
 * 上报位置按墙钟外推,流饥饿时它仍继续前进,可能越过实际已到达的音频。iOS 是否因此停流
 * 尚不确定;这些日志行让用户报告能把它与丢包区分开。仅用于诊断:不改变上报位置。
 */
internal class FeedbackPositionMonitor(
    private val sampleRate: Int,
    private val repeatNs: Long = 10_000_000_000L,
) {
    private var received: Long? = null
    private var baselineLogged = false
    private var lastOverrunNs: Long? = null

    /** [sample] 是收到的 RTP 时间戳,可能乱序到达。 */
    @Synchronized
    fun observeRtp(sample: Int) {
        val unsigned = sample.toLong() and 0xffff_ffffL
        val current = received
        if (current == null || samplesAhead(unsigned, current) > 0) received = unsigned
    }

    /**
     * 返回 [reported](刚发给 iPhone 的位置)的报告行:每流首答一次,此后当它超出最新
     * 收到采样 [OVERRUN_SECONDS] 以上时再次输出,且至多每 [repeatNs] 一次。
     */
    @Synchronized
    fun onFeedback(type: Int, connectionId: Any?, reported: Long, nowNs: Long): String? {
        val received = received ?: return null
        val ahead = samplesAhead(reported, received)
        val overrun = ahead > OVERRUN_SECONDS * sampleRate
        val first = !baselineLogged
        if (!first && !overrun) return null
        if (!first && lastOverrunNs?.let { nowNs - it < repeatNs } == true) return null
        baselineLogged = true
        if (overrun) lastOverrunNs = nowNs
        return "Audio: feedback type=$type id=${connectionId ?: 0} reported=$reported received=$received " +
            "aheadMs=${ahead * 1000 / sampleRate}" + if (overrun) " overrun=true" else ""
    }

    internal companion object {
        /** 超过一个包的余量:位置落最新包内部不算越界。 */
        const val OVERRUN_SECONDS = 0.1

        /** 32 位 RTP 时钟上从 [base] 到 [sample] 的有符号距离。 */
        fun samplesAhead(sample: Long, base: Long): Long = ((sample - base) and 0xffff_ffffL).toInt().toLong()
    }
}
