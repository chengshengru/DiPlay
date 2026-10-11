package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 热点地址族恢复（移植自 main #474，`39c052f5` 延迟 IPv6 修复）。
 *
 * 框架上报热点组创建就绪后，接口地址仍可能延迟出现（尤其是带 scope 的链路本地 IPv6）。
 * 本类在偏好 IPv6 时做有界等待轮询；在首次 TCP 超时后重新采样并给出应交替的地址族。
 *
 * API 17 适配：调用侧（CarPlayController）在 API 17–20 上禁用 IPv6 优选，
 * 老内核的 IPv6 临时地址常不可达，仅保留"超时后交替到另一地址族"的恢复路径。
 */
internal class HotspotAddressRecovery(
    private val sample: () -> List<InetAddress>,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    /**
     * 等待候选地址就绪。偏好 IPv6 且当前没有带 scope 的链路本地地址时，
     * 以 [WirelessStartupPolicy.INTERFACE_POLL_MILLIS] 为步长轮询到超时为止；
     * 超时后回退到可用地址族，不阻塞启动。
     */
    fun awaitCandidates(
        initial: List<InetAddress>,
        preferredFamily: String?,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
    ): List<InetAddress> {
        require(timeoutMillis >= 0)
        val started = nowMillis()
        val deadline = started + timeoutMillis
        var candidates = initial
        if (preferredFamily == HotspotAddressPolicy.FAMILY_IPV6 && !candidates.any(::scopedLinkLocal)) {
            log("wireless address waiting preference=IPv6 timeoutMs=$timeoutMillis")
        }
        while (true) {
            checkActive()
            if (preferredFamily != HotspotAddressPolicy.FAMILY_IPV6) return candidates
            if (candidates.any(::scopedLinkLocal)) {
                log("wireless address preference=IPv6 ready elapsedMs=${nowMillis() - started}")
                return candidates.filter { it !is Inet6Address || !it.isLinkLocalAddress || scopedLinkLocal(it) }
            }
            val remaining = deadline - nowMillis()
            if (remaining <= 0) {
                log("wireless address preference=IPv6 unavailable elapsedMs=${nowMillis() - started}; using available family")
                return candidates
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, remaining))
            checkActive()
            candidates = sample()
            checkActive()
        }
    }

    /**
     * 首次 TCP 超时后重新采样：若存在与已发布地址族相反的可用地址，
     * 返回下次应使用的地址族；组创建瞬间缺少的地址不代表单族热点。
     */
    fun nextFamily(published: InetAddress): String? {
        if (cancelled()) return null
        val candidates = sample().filter { it !is Inet6Address || scopedLinkLocal(it) }
        if (cancelled()) return null
        if (HotspotAddressPolicy.alternateFamilyCandidate(candidates, published) == null) return null
        return HotspotAddressPolicy.flipFamily(
            if (published is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4,
        )
    }

    /** 世代已失效（新连接开始/关闭）时抛出，取消等待。 */
    private fun checkActive() {
        if (cancelled()) throw InterruptedIOException("Hotspot address recovery cancelled")
    }

    /** 带 scope 的链路本地 IPv6 才是可用地址。 */
    private fun scopedLinkLocal(address: InetAddress): Boolean =
        address is Inet6Address && address.isLinkLocalAddress && address.scopeId != 0
}
