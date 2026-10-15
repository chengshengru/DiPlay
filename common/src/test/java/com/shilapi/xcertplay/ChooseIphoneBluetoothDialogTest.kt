package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.util.ReflectionHelpers

/**
 * main #799（波次十三批次E）移植：Android 蓝牙缺失、关闭、或开关留不住时，"选择 iPhone"应当说什么。
 * 分支适配：弹窗保留 Plan A 诊断信息块，因此提示断言用"包含"而非 main 的"结尾"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class ChooseIphoneBluetoothDialogTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        AppAppearanceRuntime.resetForTest()
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    private fun open(): DiPlayActivity =
        Robolectric.buildActivity(DiPlayActivity::class.java).setup().get().also { activity = it }

    private fun choosePhone(screen: DiPlayActivity): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "choosePhone")
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun texts(dialog: AlertDialog): List<String> = descendants(dialog.window!!.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    // Issue 703 的车机：车机自己的蓝牙开着，Android 没有，DiPlay 过去还让驾驶员去开开关。
    @Test fun aHeadUnitWithoutAndroidBluetoothIsToldSoInsteadOfBeingSentToASwitch() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        val screen = open()
        val dialog = choosePhone(screen)
        val shown = texts(dialog)
        assertTrue(shown.toString(), screen.getString(R.string.bluetooth_cannot_open_connections) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in shown)
        assertEquals(screen.getString(R.string.got_it), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
    }

    @Test fun bluetoothThatIsOffIsAskedForAsBefore() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        val dialog = choosePhone(screen)
        val shown = texts(dialog)
        assertTrue(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
        // 分支弹窗在首句后附带诊断信息块，因此按子串包含断言而非整句精确匹配。
        assertTrue(shown.toString(), shown.any { screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in it })
        assertEquals(screen.getString(R.string.open_bluetooth), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    // Issue 581 的车机：Android 设置页里的开关会自己关掉。
    @Test fun bluetoothStillOffAfterAVisitToItsSettingsGetsTheHint() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        val hint = screen.getString(R.string.bluetooth_will_not_stay_on_hint)
        val first = choosePhone(screen)
        assertFalse(texts(first).toString(), texts(first).any { hint in it })
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // 分支按钮可能经应用层 enable() 请求开启；无论结果如何，回来时仍按"关闭"场景复查。
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)

        val second = choosePhone(screen)
        val message = texts(second).single { screen.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first) in it }
        assertTrue(message, hint in message)
        assertEquals(screen.getString(R.string.open_bluetooth), second.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    // 选择"稍后"不等于去过设置页：不该提示开关开启失败。
    @Test fun choosingLaterDoesNotBringTheHint() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        val screen = open()
        choosePhone(screen).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val again = texts(choosePhone(screen))
        assertFalse(again.toString(), again.any { screen.getString(R.string.bluetooth_will_not_stay_on_hint) in it })
    }

    @Test fun bluetoothThatIsOnGoesOnToPairing() {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        val screen = open()
        val shown = texts(choosePhone(screen))
        assertTrue(shown.toString(), screen.getString(R.string.pair_your_iphone) in shown)
        assertFalse(shown.toString(), screen.getString(R.string.turn_on_bluetooth) in shown)
    }
}
