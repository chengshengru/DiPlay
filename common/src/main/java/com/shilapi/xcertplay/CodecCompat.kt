// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * API 17-safe codec enumeration.
 *
 * `MediaCodecList(REGULAR_CODECS)` and its `codecInfos` getter are both API 21+. On a Jelly Bean
 * head unit the dalvik verifier resolves neither, so reaching either one throws
 * [NoSuchMethodError] *at the call site* — and that is an [Error], not an [Exception], so a
 * `catch (RuntimeException)`/`catch (Exception)` around the call lets it escape and kills the
 * process (GM C140A, 2026-10-12: `CarPlayHostActivity.textureUnsafeVideoDecoder`).
 *
 * API 17-20 fall back to the static `getCodecCount()`/`getCodecInfoAt(i)` pair (API 16+). Those are
 * deprecated on modern platforms but still exist in the compileSdk 36 `android.jar`, and the same
 * strategy is already used by the `MtkDecoderTuning` codec scan in `shared`.
 *
 * Both branches must stay inside this one function: callers receive a plain
 * `List<MediaCodecInfo>` and never touch an API-21 member themselves, which is what keeps the
 * linkage error from ever being *executed* on API 17.
 */
// The fallback deliberately reaches for APIs deprecated on current platforms.
@Suppress("DEPRECATION")
internal object CodecCompat {

    /**
     * Every codec the platform lists, encoders included — the raw list callers filter themselves.
     *
     * On API 21+ this is `MediaCodecList.REGULAR_CODECS`, i.e. non-encoder non-alias entries; the
     * API 17 fallback has no alias concept but still returns encoders, which is why callers keep
     * their own `!info.isEncoder` filter.
     */
    fun all(): List<MediaCodecInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } else {
            (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }
        }
}
