package com.shilapi.xcertplay.airplay

/**
 * 从 iPhone 自身下行包测量其在一个音频流上使用的 RTP 时钟，供诊断报告确认
 * 该流上的麦克风云台上行必须按哪个时钟计数。
 */
internal class RtpClockProbe(private val windowNs: Long = 2_000_000_000L) {
    private var firstSample = 0L
    private var firstNs = 0L
    private var started = false
    private var done = false

    /** 在 [windowNs] 的包观测后返回一次测得的时钟（Hz），其余情况返回 null。 */
    @Synchronized
    fun observe(sample: Int, nowNs: Long): Int? {
        if (done) return null
        val unsigned = sample.toLong() and 0xffff_ffffL
        if (!started) {
            started = true
            firstSample = unsigned
            firstNs = nowNs
            return null
        }
        val elapsedNs = nowNs - firstNs
        if (elapsedNs < windowNs) return null
        done = true
        val ticks = (unsigned - firstSample) and 0xffff_ffffL
        return Math.round(ticks * 1_000_000_000.0 / elapsedNs).toInt()
    }
}
