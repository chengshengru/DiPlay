package com.shilapi.xcertplay.settings

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale

/**
 * Named display-quality presets for the head unit's canvas, plus the top-bar avoidance inset.
 *
 * Ported from HeadUnitWireless-CarPlay (GPL-3.0) `GolfDisplaySettings.Quality`, but rebuilt on this
 * port's existing [CarPlayDisplayScale] (which already offers arbitrary 30%–160% percentages) so the
 * presets are a thin, locale-free mapping: the settings UI keeps the localized names and composes the
 * preview from [previewSize]. This mirrors B's design where a saved preset only takes effect on the
 * next connection.
 */
object DisplayQualityPresets {
    /** Height of the head unit's top bar that the canvas avoids when avoidance is on (B: 64dp). */
    const val TOP_BAR_INSET_DP = 64

    enum class Quality(val id: String, val scaleTenths: Int) {
        BALANCED("balanced", 8),
        SMOOTH("smooth", 6),
        NATIVE("native", 10),
    }

    fun fromId(id: String?): Quality = when (id) {
        Quality.SMOOTH.id -> Quality.SMOOTH
        Quality.NATIVE.id -> Quality.NATIVE
        else -> Quality.BALANCED
    }

    /** The preset whose scale matches [tenths], or null when [tenths] is a custom (non-preset) value. */
    fun fromTenths(tenths: Int): Quality? = Quality.entries.firstOrNull { it.scaleTenths == tenths }

    /** Applies the preset's scale to [display]; NATIVE keeps the panel's own pixels. */
    fun applyTo(display: AirPlayDisplayConfig, quality: Quality): AirPlayDisplayConfig =
        CarPlayDisplayScale.apply(display, quality.scaleTenths)

    /** The "WxH" the preset negotiates on a [width]x[height] panel, for the settings preview. */
    fun previewSize(quality: Quality, width: Int, height: Int): Pair<Int, Int> {
        val scaled = applyTo(AirPlayDisplayConfig(width, height), quality)
        return scaled.widthPixels to scaled.heightPixels
    }

    /** The canvas height to leave for the top bar, in physical pixels (0 when avoidance is off). */
    fun topBarInsetPx(density: Float, avoidTopBar: Boolean): Int =
        if (avoidTopBar) (TOP_BAR_INSET_DP * density).toInt() else 0
}
