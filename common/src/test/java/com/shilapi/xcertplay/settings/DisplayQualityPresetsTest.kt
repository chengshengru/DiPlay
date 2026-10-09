package com.shilapi.xcertplay.settings

import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic parity with HeadUnitWireless-CarPlay's display tests (`UniversalDisplayTest`,
 * `GolfNativeDisplayTest`), which assert the same preset → canvas sizes. The Robolectric-only parts
 * of those tests drive B's activity internals and are not portable.
 */
class DisplayQualityPresetsTest {
    @Test fun presetScalesMatchTheLegacyTenths() {
        assertEquals(8, DisplayQualityPresets.Quality.BALANCED.scaleTenths)
        assertEquals(6, DisplayQualityPresets.Quality.SMOOTH.scaleTenths)
        assertEquals(10, DisplayQualityPresets.Quality.NATIVE.scaleTenths)
    }

    @Test fun previewSizesMatchTheReferenceCanvas() {
        assertEquals(
            800 to 480,
            DisplayQualityPresets.previewSize(DisplayQualityPresets.Quality.NATIVE, 800, 480),
        )
        assertEquals(
            1024 to 576,
            DisplayQualityPresets.previewSize(DisplayQualityPresets.Quality.BALANCED, 1280, 720),
        )
        assertEquals(
            614 to 360,
            DisplayQualityPresets.previewSize(DisplayQualityPresets.Quality.SMOOTH, 1024, 600),
        )
    }

    @Test fun applyToMatchesTheUnderlyingScale() {
        val panel = com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1920, 978)
        assertEquals(
            CarPlayDisplayScale.apply(panel, 8),
            DisplayQualityPresets.applyTo(panel, DisplayQualityPresets.Quality.BALANCED),
        )
    }

    @Test fun idAndTenthsRoundTrip() {
        for (quality in DisplayQualityPresets.Quality.entries) {
            assertEquals(quality, DisplayQualityPresets.fromId(quality.id))
            assertEquals(quality, DisplayQualityPresets.fromTenths(quality.scaleTenths))
        }
        assertEquals(DisplayQualityPresets.Quality.BALANCED, DisplayQualityPresets.fromId("unknown"))
        assertNull(DisplayQualityPresets.fromTenths(7))
    }

    @Test fun topBarInsetIsDensityScaledAndOffWhenDisabled() {
        assertEquals(64, DisplayQualityPresets.TOP_BAR_INSET_DP)
        assertEquals(128, DisplayQualityPresets.topBarInsetPx(2f, avoidTopBar = true))
        assertEquals(0, DisplayQualityPresets.topBarInsetPx(2f, avoidTopBar = false))
    }
}
