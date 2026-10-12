package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExperimentalTogglesSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
    }

    // 实验性①：USB 连接页实时日志，默认关、位于高级/实验性卡、切换即持久化。
    @Test fun usbRealtimeLogIsOptInAndInAdvanced() {
        AirPlayPersistence.saveUsbRealtimeLog(context, false)
        openAdvanced()
        val sw = switches().single { it.contentDescription == activity.getString(R.string.settings_usb_realtime_log) }

        assertFalse(sw.isChecked)
        sw.performClick()

        assertTrue(sw.isChecked)
        assertTrue(AirPlayPersistence.loadUsbRealtimeLog(context))
    }

    // 实验性②：无线连接绕过蓝牙开关配置，默认关、位于高级/实验性卡、切换即持久化。
    @Test fun wirelessSkipBluetoothIsOptInAndInAdvanced() {
        AirPlayPersistence.saveWirelessSkipBluetoothConfig(context, false)
        openAdvanced()
        val sw = switches().single { it.contentDescription == activity.getString(R.string.settings_wireless_skip_bluetooth_config) }

        assertFalse(sw.isChecked)
        sw.performClick()

        assertTrue(sw.isChecked)
        assertTrue(AirPlayPersistence.loadWirelessSkipBluetoothConfig(context))
    }

    // 复用 SystemBarSettingsTest.harmanFullscreenIsOptInAndInAdvancedSettings 的导航：
    // 进入设置→点开 Display 子分类→返回分类列表→展开 Advanced（先进入子分类，onBackPressed 才回到列表而非退出）。
    private fun openAdvanced() {
        controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup()
        descendants(activity.window.decorView)
            .single { it.contentDescription == activity.getString(
                R.string.settings_open_category,
                activity.getString(R.string.settings_display),
            ) }
            .performClick()
        activity.onBackPressedDispatcher.onBackPressed()
        descendants(activity.window.decorView)
            .single { it.contentDescription == activity.getString(
                R.string.settings_open_category,
                activity.getString(R.string.settings_advanced),
            ) }
            .performClick()
    }

    private fun switches() = descendants(activity.window.decorView).filterIsInstance<SwitchCompat>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
