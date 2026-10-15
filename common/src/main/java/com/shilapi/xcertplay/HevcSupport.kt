package com.shilapi.xcertplay

import android.media.MediaFormat
import android.os.Build

/**
 * HEVC 仅在有硬件解码器能播放时才值得申请。没有硬件解码器时,Android 会自行选择软件解码器,
 * 低端车机无法在播放音乐的同时跟上它的速度(#727)。
 */
internal object HevcSupport {
    data class Decoder(val name: String, val hardware: Boolean)

    /** 驱动程序的软件 HEVC 选择在有任何解码器时都保留 HEVC。 */
    fun usable(requested: Boolean, softwareChosen: Boolean, decoders: List<Decoder>): Boolean =
        requested && (decoders.any { it.hardware } || softwareChosen && decoders.isNotEmpty())

    /** 走 [CodecCompat] 而非直调 MediaCodecList：后者是 API 21+，API 17 上会抛 NoSuchMethodError。 */
    fun decoders(): List<Decoder> = runCatching {
        CodecCompat.all().filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }.map { info ->
            Decoder(info.name, if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."))
        }
    }.getOrDefault(emptyList())
}
