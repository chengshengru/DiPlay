package com.shilapi.xcertplay.transport

/**
 * 每条收到的 iAP2 消息对应的报告行。Now-playing（0x5001）与路线引导（0x5201）每秒左右重复
 * 一次：两者合计曾占一份 25,000 行报告的一半以上（#473），被 GitHub 尺寸限制截断的报告会
 * 丢失关键行（#536、#606）。这两类消息先按 [first] 次逐条记录，之后每 [every] 次带计数记一条。
 */
internal class Iap2RxLog(private val first: Int = 5, private val every: Int = 100) {
    private val counts = HashMap<Int, Int>()

    /** 返回要记录的行；周期性消息被节流时返回 null。 */
    fun line(messageId: Int): String? {
        val id = "0x" + messageId.toString(16).padStart(4, '0')
        if (messageId !in PERIODIC) return "iap2 rx=$id"
        val count = (counts[messageId] ?: 0) + 1
        counts[messageId] = count
        return when {
            count <= first -> "iap2 rx=$id"
            count % every == 0 -> "iap2 rx=$id count=$count"
            else -> null
        }
    }

    private companion object {
        val PERIODIC = setOf(0x5001, 0x5201)
    }
}
