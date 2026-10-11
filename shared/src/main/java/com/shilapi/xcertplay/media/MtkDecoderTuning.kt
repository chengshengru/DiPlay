package com.shilapi.xcertplay.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** 旧 MTK ACodec 实现暴露的可选解码器模式(main #608)。 */
internal enum class MtkDecoderMode(val diagnosticName: String) {
    NONE("off"),
    LOW_LATENCY("low-latency"),
    LOW_LATENCY_NO_REORDER("low-latency+no-reorder"),
    ;

    fun fallback(): MtkDecoderMode = when (this) {
        LOW_LATENCY_NO_REORDER -> LOW_LATENCY
        LOW_LATENCY -> NONE
        NONE -> NONE
    }

    fun attempts(): List<MtkDecoderMode> = when (this) {
        LOW_LATENCY_NO_REORDER -> listOf(LOW_LATENCY_NO_REORDER, LOW_LATENCY, NONE)
        LOW_LATENCY -> listOf(LOW_LATENCY, NONE)
        NONE -> listOf(NONE)
    }
}

/** 旧 MTK OMX 与专用 Codec2 低延迟解码器的检测与调优(main #608)。 */
object MtkDecoderTuning {
    const val LOW_LATENCY_KEY = "vdec-lowlatency"

    // MTK ACodec 使用的拼写,虽然它映射到 NoReorderMode。
    const val NO_REORDER_KEY = "vdec-no-record"

    fun isDecoderName(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true) ||
            name.startsWith("c2.mtk.", ignoreCase = true)

    /** 发布的 vdec-* 桥接是 ACodec/OMX 扩展,不是 Codec2 厂商参数。 */
    fun supportsLegacyAcodecKeys(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true)

    fun isCodec2LowLatencyDecoder(name: String, advertisesLowLatency: Boolean): Boolean =
        name.startsWith("c2.mtk.", ignoreCase = true) &&
            (advertisesLowLatency || name.endsWith(".lowlatency", ignoreCase = true) ||
                name.endsWith(".low_latency", ignoreCase = true))

    /** 优先专用 MTK Codec2 低延迟组件;否则用默认的旧 OMX 解码器。 */
    internal fun selectedDecoder(candidates: List<MtkDecoderCandidate>): MtkDecoderCandidate? =
        candidates.firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }
            ?: candidates.firstOrNull()?.takeIf { supportsLegacyAcodecKeys(it.name) }

    /** [mime] 要暴露的 MTK 解码器路径,两条安全路径都不可用时为 null。 */
    fun selectedDecoderName(mime: String): String? = candidates(mime).let(::selectedDecoder)?.name

    /** [mime] 的专用 Codec2 低延迟组件,显式选中而不是使用 ACodec 键。 */
    fun codec2LowLatencyDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }?.name

    /** 仅当 [mime] 的默认解码器能消费 MTK 的旧 ACodec 键时返回它。 */
    fun legacyAcodecDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull()?.name?.takeIf(::supportsLegacyAcodecKeys)

    // DiPlay 运行期间解码器列表不变,而设置页每次渲染都会询问(main #608 缓存提交)。
    private val candidatesByMime = java.util.concurrent.ConcurrentHashMap<String, List<MtkDecoderCandidate>>()

    private fun candidates(mime: String): List<MtkDecoderCandidate> =
        candidatesByMime.getOrPut(mime.lowercase()) { listCandidates(mime) }

    private fun listCandidates(mime: String): List<MtkDecoderCandidate> = runCatching {
        // MediaCodecList(REGULAR_CODECS).codecInfos 是 API 21+;API 17-20 回退到
        // getCodecCount()/getCodecInfoAt(i)(API 16+)。低版本没有 isAlias/低延迟特性,
        // 一律按 lowLatency=false 处理;只有 OMX.MTK. 路径在 API 17 上有意义。
        val infos: List<MediaCodecInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } else {
            // MediaCodecList() 无参构造在 SDK 36 的 android.jar 中已被移除,
            // 而 getCodecCount()/getCodecInfoAt(i) 是 static,直接调用即可(API 16+)。
            (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }
        }
        infos.mapNotNull { info ->
            if (info.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) ||
                info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
            val lowLatency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            }.getOrDefault(false) else false
            MtkDecoderCandidate(info.name, lowLatency)
        }
    }.getOrDefault(emptyList())
}

internal data class MtkDecoderCandidate(val name: String, val lowLatency: Boolean = false)

internal fun appliedMtkDecoderMode(requested: MtkDecoderMode, codecName: String): MtkDecoderMode =
    requested.takeIf { MtkDecoderTuning.supportsLegacyAcodecKeys(codecName) } ?: MtkDecoderMode.NONE

internal fun MtkDecoderMode.formatKeys(): List<String> = when (this) {
    MtkDecoderMode.NONE -> emptyList()
    MtkDecoderMode.LOW_LATENCY -> listOf(MtkDecoderTuning.LOW_LATENCY_KEY)
    MtkDecoderMode.LOW_LATENCY_NO_REORDER ->
        listOf(MtkDecoderTuning.LOW_LATENCY_KEY, MtkDecoderTuning.NO_REORDER_KEY)
}
