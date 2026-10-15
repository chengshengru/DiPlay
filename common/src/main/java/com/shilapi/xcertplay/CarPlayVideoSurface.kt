package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import kotlin.math.roundToInt

internal enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/**
 * Decide from the attached window, not the declared manifest flag or vehicle model. Smooth video also
 * needs a SurfaceView: only the compositor honours the frame timestamps it releases with. Direct video
 * output (a setting, main #496) picks one too: frames go straight to the compositor instead of through
 * the app's own drawing, which can save about a refresh。当某解码器的画面无法作为纹理交给应用的绘制时
 * ([textureUnsafeDecoder]),SurfaceView 同样是唯一选择。
 */
internal fun carPlayVideoSurfaceMode(
    hardwareAccelerated: Boolean,
    smoothVideo: Boolean = false,
    directVideoOutput: Boolean = false,
    textureUnsafeDecoder: Boolean = false,
): CarPlayVideoSurfaceMode =
    if (hardwareAccelerated && !smoothVideo && !directVideoOutput && !textureUnsafeDecoder) CarPlayVideoSurfaceMode.TEXTURE
    else CarPlayVideoSurfaceMode.SURFACE

/** 已安装的视频解码器,按 MediaCodecList 顺序。 */
internal data class VideoDecoderInfo(val name: String, val types: List<String>)

/**
 * [mimes] 中(按序取第一个列出它的)解码器,当其画面无法通过 TextureView 呈现时返回其名称,否则 null。
 * emteria 的 Android 17(Raspberry Pi 5)用 c2.ffmpeg.h264.decoder 解码,它被报告为硬件加速:应用的渲染器
 * 在画出第一帧纹理后立即以"Invalid GrBackendTexture"中止,而 SurfaceView 能正常合成同样的画面。其它
 * FFmpeg 背景的 Codec2 解码器输出相同,故按名称前缀匹配该家族。
 */
internal fun textureUnsafeDecoder(decoders: List<VideoDecoderInfo>, mimes: List<String>): String? =
    mimes.asSequence()
        .mapNotNull { mime -> decoders.firstOrNull { decoder -> decoder.types.any { it.equals(mime, ignoreCase = true) } } }
        .firstOrNull { it.name.startsWith(FFMPEG_CODEC2_PREFIX, ignoreCase = true) }
        ?.name

private const val FFMPEG_CODEC2_PREFIX = "c2.ffmpeg."

/** Integer compositor bounds retain overscan when a CarPlay view area crops the canvas. */
internal data class CarPlaySurfaceBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun from(content: CarPlayVideoLayout): CarPlaySurfaceBounds {
            val left = content.left.roundToInt()
            val top = content.top.roundToInt()
            return CarPlaySurfaceBounds(left, top,
                ((content.left + content.width).roundToInt() - left).coerceAtLeast(1),
                ((content.top + content.height).roundToInt() - top).coerceAtLeast(1))
        }
    }
}

/**
 * Smooth video's starting delay over the link's base delay; it then adjusts to the frames the decoder
 * releases. On my Tang the Qualcomm decoder released a frame only after about two more had been queued
 * (p90 69-77 ms at about 56 fps, runs A1/A2 in docs/SMOOTH_WIRELESS.md), so it starts at three frame
 * intervals plus 40 ms: 90 ms at 60 fps.
 */
internal fun smoothVideoDelayMillis(fps: Int): Int = 40 + 3_000 / fps.coerceIn(30, 60)

/**
 * 波次十一批次D（main #669）：叠在返回后的 SurfaceView 上的最后一帧是否可以撤下——自它返回后已有帧渲染
 * （[rendered] 从 [baseline] 前移，新解码器从 0 起也算），或已超过 [maxMillis]。
 */
internal fun lastPictureDone(rendered: Long, baseline: Long, elapsedMillis: Long, maxMillis: Long): Boolean =
    (rendered > 0 && rendered != baseline) || elapsedMillis > maxMillis

/**
 * A retained background session can be adopted only when its sink's pacing matches the video view this
 * host built: the sink fixes pacing at creation and the host picks its view once.
 */
internal fun backgroundSessionMatchesView(sinkPaces: Boolean, viewSmoothVideo: Boolean): Boolean =
    sinkPaces == viewSmoothVideo

/** Texture wrappers are ours to release; SurfaceHolder surfaces belong to the framework. */
internal class CarPlayVideoSurfaceOwner<T : Any>(
    private val detach: (T) -> Unit,
    private val release: (T) -> Unit,
) {
    var current: T? = null
        private set
    private var owned = false

    fun replace(surface: T, releaseOnDetach: Boolean) {
        if (current === surface) return
        clear()
        current = surface
        owned = releaseOnDetach
    }

    /** Ignore a late callback from a surface replaced by a newer output. */
    fun clear(surface: T) {
        if (current === surface) clear()
    }

    fun clear() {
        val previous = current ?: return
        val releasePrevious = owned
        current = null
        owned = false
        try {
            detach(previous)
        } finally {
            if (releasePrevious) release(previous)
        }
    }
}
