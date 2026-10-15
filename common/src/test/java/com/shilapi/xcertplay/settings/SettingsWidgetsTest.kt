package com.shilapi.xcertplay.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun constructingAndChangingASwitchDoesNotSaveTheInitialValue() {
        for (theme in SettingsTheme.entries) {
            val saved = mutableListOf<Boolean>()
            val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme,
                onChanged = saved::add)
            assertTrue(saved.isEmpty())
            row.switch.isChecked = false
            row.switch.isChecked = false
            assertEquals(listOf(false), saved)
        }
    }

    @Test fun choicesEmitOnlyNewSelectionsAndIgnoreClearingTheSelection() {
        val saved = mutableListOf<MfiTarget>()
        val row = ConnectionSettingsSection.createMfiTargetChoice(context, MfiTarget.LOCAL,
            onSelected = saved::add)
        assertTrue(saved.isEmpty())
        val target = views(row.container).filterIsInstance<RadioButton>().first { it.tag == MfiTarget.USB_CH341 }
        row.radioGroup.check(target.id)
        row.radioGroup.check(target.id)
        row.radioGroup.clearCheck()
        assertEquals(listOf(MfiTarget.USB_CH341), saved)
    }

    @Test fun resolutionDisplayAndSliderStayWithinTheSameRangeWithoutSavingDuringConstruction() {
        for (initial in listOf(-1, 73, 999)) {
            val saved = mutableListOf<Int>()
            val row = SettingsWidgets.createResolutionSlider(context, initial, onPercentChanged = saved::add)
            val expected = initial.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
            assertEquals(expected - CarPlayDisplayScale.MIN_PERCENT, row.seekBar.progress)
            assertEquals(expected.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.isEmpty())
            row.seekBar.progress = 0
            row.seekBar.progress = Int.MAX_VALUE
            assertEquals(CarPlayDisplayScale.MAX_PERCENT.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.all { it in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT })
            assertEquals(CarPlayDisplayScale.MAX_PERCENT, saved.last())
        }
    }

    @Test fun overlayKeepsTheExistingGreenControlsAndLabelMetrics() {
        val row = ConnectionSettingsSection.createWirelessCarPlayRow(context, true, onChanged = {})
        val label = (row.rowView as ViewGroup).getChildAt(0) as TextView
        assertEquals(20f, label.textSize / context.resources.displayMetrics.scaledDensity, 0.01f)
        assertFalse(label.includeFontPadding)
        assertEquals(Color.rgb(170, 180, 190), label.currentTextColor)
        // 开关拇指统一白色；绿色 accent 现在由开启态轨道承载（自绘 Drawable，无 tint）。
        assertEquals(Color.WHITE, (row.switch.thumbDrawable as SettingsWidgets.CircleThumbDrawable).colors
            .getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0))
        assertEquals(Color.rgb(78, 143, 102), (row.switch.trackDrawable as SettingsWidgets.PillTrackDrawable).colors
            .getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0))
        val heading = SettingsWidgets.createCategoryHeader(context, "Heading")
        assertEquals(Color.rgb(127, 205, 154), heading.currentTextColor)
        assertFalse(heading.includeFontPadding)
    }

    // main 9c7fda97：大开关 60x34dp 轨道与禁用态染色断言（作者：TRAE AI）。
    // 拇指/轨道改为自绘 Drawable：颜色在 Drawable 内按 view state 解析，不再走 tint list。
    @Test fun switchesUseTheLargeTrackAndMarkTheDisabledState() {
        for (theme in SettingsTheme.entries) {
            val switch = SettingsWidgets.createSwitchRow(context, "Title", "Details", false, theme, onChanged = {}).switch
            switch.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            assertTrue(switch.measuredWidth >= theme.dp(context, 60))
            assertTrue(switch.measuredHeight >= theme.dp(context, 34))
            val track = switch.trackDrawable as SettingsWidgets.PillTrackDrawable
            val enabledOff = track.colors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0)
            val disabled = track.colors.getColorForState(intArrayOf(), 0)
            assertEquals(theme.trackOff, enabledOff)
            assertNotEquals(enabledOff, disabled)
            // 开启态轨道必须是实色且与关闭态明显不同。
            val enabledOn = track.colors.getColorForState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0)
            assertEquals(theme.accentTrack, enabledOn)
            assertEquals(Color.alpha(enabledOn), 255)
            assertTrue(Math.abs(Color.red(enabledOn) - Color.red(enabledOff)) +
                Math.abs(Color.green(enabledOn) - Color.green(enabledOff)) +
                Math.abs(Color.blue(enabledOn) - Color.blue(enabledOff)) >= 60)
        }
    }

    // 亮色主题回归：CARD 设置页开关必须跟随设备亮/暗 palette——传入 LIGHT 后关闭态用
    // 浅灰轨道、开启态用亮蓝轨道、标签用深色文字，而不是 SettingsTheme.CARD 写死的
    // 暗色值（否则亮色模式下开关与文字都还是暗色样式）。
    @Test fun cardSwitchAdoptsTheAdaptivePaletteTrackColorsInLightMode() {
        val darkSwitch = SettingsWidgets.createSwitchRow(
            context, "Title", "Details", false, SettingsTheme.CARD, onChanged = {}).switch
        val lightRow = SettingsWidgets.createSwitchRow(
            context, "Title", "Details", false, SettingsTheme.CARD,
            palette = DiPlayPalette.LIGHT, onChanged = {})
        val lightSwitch = lightRow.switch
        val darkTrack = darkSwitch.trackDrawable as SettingsWidgets.PillTrackDrawable
        val lightTrack = lightSwitch.trackDrawable as SettingsWidgets.PillTrackDrawable
        // 默认（不传 palette）仍用 SettingsTheme 写死的深蓝灰关闭轨道。
        assertEquals(SettingsTheme.CARD.trackOff,
            darkTrack.colors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0))
        // 传入 LIGHT palette 后用浅灰关闭轨道，且与深蓝灰明显不同。
        val lightOff = lightTrack.colors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0)
        assertEquals(DiPlayPalette.LIGHT.trackOff, lightOff)
        assertNotEquals(SettingsTheme.CARD.trackOff, lightOff)
        assertTrue(Math.abs(Color.red(lightOff) - Color.red(SettingsTheme.CARD.trackOff)) +
            Math.abs(Color.green(lightOff) - Color.green(SettingsTheme.CARD.trackOff)) +
            Math.abs(Color.blue(lightOff) - Color.blue(SettingsTheme.CARD.trackOff)) >= 40)
        // 开启态也跟随：亮色下用亮蓝而不是深蓝。
        val lightOn = lightTrack.colors.getColorForState(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0)
        assertEquals(DiPlayPalette.LIGHT.accentTrack, lightOn)
        // 行文字跟随亮色 palette：标签深色、说明中灰，而不是 CARD 写死的近白。
        val texts = views(lightRow.rowView).filterIsInstance<TextView>().toList()
        assertEquals(DiPlayPalette.LIGHT.primaryText, texts[0].currentTextColor)
        assertEquals(DiPlayPalette.LIGHT.secondaryText, texts[1].currentTextColor)
    }

    // 滑块回归：拇指必须是正圆 Drawable 且直径固定，视图被拉高也不会变椭圆。
    @Test fun switchThumbStaysACircleWhenTheRowStretchesIt() {
        for (theme in SettingsTheme.entries) {
            val switch = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme, onChanged = {}).switch
            val thumb = switch.thumbDrawable as SettingsWidgets.CircleThumbDrawable
            assertEquals(theme.dp(context, 28), thumb.intrinsicWidth)
            assertEquals(theme.dp(context, 28), thumb.intrinsicHeight)
            // 开/关两态拇指都是白色（空 state 数组会命中禁用项，必须显式带 state_enabled）。
            assertEquals(Color.WHITE, thumb.colors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0))
            assertEquals(Color.WHITE, thumb.colors.getColorForState(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0))
            // 容器把 switch 拉高时，拇指 bounds 变成非正方形，仍取短边画圆。
            switch.measure(
                View.MeasureSpec.makeMeasureSpec(theme.dp(context, 60), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(theme.dp(context, 56), View.MeasureSpec.EXACTLY),
            )
            switch.layout(0, 0, switch.measuredWidth, switch.measuredHeight)
            assertEquals(theme.dp(context, 56), switch.height)
        }
    }

    // 回归：真机截图里拇指曾被容器拉成椭圆——这里用 SwitchCompat 真实测量出的
    // 非正方形 thumb bounds（28x34dp）单独光栅化，验证画出来的是正圆而不是椭圆。
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun switchThumbPaintsACircleEvenWhenTheRowStretchesTheSwitch() {
        val width = SettingsTheme.CARD.dp(context, 60)
        val height = SettingsTheme.CARD.dp(context, 56)
        val switch = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, SettingsTheme.CARD,
            onChanged = {}).switch
        switch.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        switch.layout(0, 0, width, height)
        switch.jumpDrawablesToCurrentState()
        // SwitchCompat 只在 onDraw 里给 Drawable 赋 bounds；先跑一次绘制拿到真实 bounds。
        switch.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))

        // row 高 56dp 时 SwitchCompat 把 thumb bounds 拉成 28x34dp（非正方形）：旧实现
        // 的 OVAL GradientDrawable 在这种 bounds 下会画成椭圆。
        val thumbSide = SettingsTheme.CARD.dp(context, 28)
        val thumbBounds = switch.thumbDrawable.bounds
        assertEquals(thumbSide, thumbBounds.width())
        assertTrue(thumbBounds.height() > thumbBounds.width())
        val trackBounds = switch.trackDrawable.bounds
        assertEquals(width, trackBounds.width())
        assertEquals(SettingsTheme.CARD.dp(context, 34), trackBounds.height())

        val thumb = switch.thumbDrawable
        val bitmap = Bitmap.createBitmap(thumbBounds.width(), thumbBounds.height(), Bitmap.Config.ARGB_8888)
        thumb.setBounds(0, 0, thumbBounds.width(), thumbBounds.height())
        thumb.draw(Canvas(bitmap))

        val radius = thumbSide / 2
        val centerX = thumbBounds.width() / 2
        val centerY = thumbBounds.height() / 2
        // 圆心与半径内是白色拇指。
        assertEquals(Color.WHITE, bitmap.getPixel(centerX, centerY))
        assertEquals(Color.WHITE, bitmap.getPixel(centerX + radius / 2, centerY))
        assertEquals(Color.WHITE, bitmap.getPixel(centerX, centerY + radius / 2))
        // 沿对角线半径外（椭圆会覆盖到的位置）必须还是透明：证明是正圆而非椭圆。
        assertEquals(0, bitmap.getPixel(centerX + radius - 2, centerY + radius - 2))
        assertEquals(0, bitmap.getPixel(centerX + radius / 2, centerY + radius + 2))
        assertEquals(0, bitmap.getPixel(0, 0))
    }

    @Test fun cardDescriptionsKeepMultilineSpacingAndTheirAccessibleTitle() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "First line\nSecond line", false,
            SettingsTheme.CARD, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title", "First line\nSecond line"), texts.map { it.text.toString() })
        assertTrue(texts.all { it.lineSpacingExtra == SettingsTheme.CARD.dp(context, 3).toFloat() })
        assertEquals("Title", row.switch.contentDescription)
    }

    @Test @Config(sdk = [28]) fun android9HotspotChoiceDoesNotExposeUnavailableWifiDirect() {
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.MANUAL,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI), options.map { it.tag })
        assertEquals(WirelessHotspotMode.MANUAL, options.first { it.isChecked }.tag)
    }

    /** ExistingWifiManager 依赖 API 21 的 ConnectivityManager.NetworkCallback，API 17-20 设备上不得呈现。 */
    @Test @Config(sdk = [17]) fun android422HotspotChoiceDoesNotExposeExistingWifi() {
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.MANUAL,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL), options.map { it.tag })
        assertEquals(WirelessHotspotMode.MANUAL, options.first { it.isChecked }.tag)
    }

    @Test fun carHotspotIsTheFirstAndRecommendedHotspotChoice() {
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.WIFI_P2P,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI),
            options.map { it.tag })
        assertEquals("Built-in car hotspot (recommended)", options.first().text.toString())
        assertEquals(WirelessHotspotMode.WIFI_P2P, options.first { it.isChecked }.tag)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
