package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** main c2e1b22e（波次十三批次G）移植：选定前"选择 iPhone"按钮不带占位名，选定后带设备名。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ChooseIphoneTitleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() = clearSettings()

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(page: String): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", page),
    ).setup().get().also { opened += it }

    /** 连接设置、引导页的 iPhone 步骤、设置 → 连接：每处都有这个按钮。 */
    private fun pages(): List<Pair<String, DiPlayActivity>> = listOf(
        "connection setup" to open("connection"),
        "setup guide" to open("setup").also {
            ReflectionHelpers.setField(it, "setupStep", SetupGuide.STEP_IPHONE)
            ReflectionHelpers.callInstanceMethod<Unit>(it, "render")
        },
        "settings" to open("settings").also {
            ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(it, "render")
        },
    )

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun chooseButtons(screen: DiPlayActivity): List<String> = texts(screen).filter {
        it == screen.getString(R.string.choose_iphone) || it.startsWith(screen.getString(R.string.choose_iphone_prefix))
    }

    @Test fun beforeAnIphoneIsChosenTheButtonNamesNone() {
        for ((name, screen) in pages()) {
            assertEquals(name, listOf(screen.getString(R.string.choose_iphone)), chooseButtons(screen))
            assertFalse(name, texts(screen).any { "Your iPhone" in it })
        }
    }

    @Test fun theSetupGuideNoLongerNamesAnIphoneUnderNoIphoneChosenYet() {
        val (_, guide) = pages()[1]
        val shown = texts(guide)
        val none = shown.indexOf(guide.getString(R.string.setup_iphone_none))
        assertTrue(shown.toString(), none >= 0)
        assertEquals(guide.getString(R.string.choose_iphone), shown[none + 1])
    }

    @Test fun aChosenIphoneIsNamedOnTheButton() {
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "Test iPhone")
        for ((name, screen) in pages()) {
            assertEquals(name, listOf(screen.getString(R.string.choose_iphone_prefix) + "Test iPhone"), chooseButtons(screen))
        }
    }

    // 占位名过去在所有语言里都是英文：繁体中文曾显示"選擇 iPhone · Your iPhone"。
    @Config(qualifiers = "zh-rTW-w1000dp-h700dp-land")
    @Test fun inTraditionalChineseTheButtonIsAllChinese() {
        for ((name, screen) in pages()) {
            assertEquals(name, listOf("選擇 iPhone"), chooseButtons(screen))
        }
    }
}
