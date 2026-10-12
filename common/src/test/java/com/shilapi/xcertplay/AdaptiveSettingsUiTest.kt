package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AdaptiveSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun compactSettingsOpenOnAnEverydayDriverOverview() {
        val screen = openSettings()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_overview) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun readinessAsksForAnIphoneBeforeWirelessCanConnect() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val screen = openSettings()
        // Robolectric has no CarPlay authentication assets, which is a setup error of its own.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_title) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_action) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
    }

    @Test fun chosenPhoneWithMissingManualHotspotOffersConnectionSetup() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.setup_needs_attention) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun searchOpensTheCategoryThatOwnsTheSetting() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val results = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "searchSettings",
            ReflectionHelpers.ClassParameter(List::class.java, index),
            ReflectionHelpers.ClassParameter(String::class.java, "FRAME rate"))
        val frameRate = results.single {
            ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.frame_rate)
        }

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(frameRate.javaClass, frameRate))

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
    }

    @Test fun headerSearchBoxListsMatchesAsYouTypeAndOpensTheChosenSetting() {
        val screen = openSettings()
        fun box() = descendants(screen.window.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == screen.getString(R.string.settings_search) }
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(box().hasFocus())

        val editor = box()
        box().setText("FRAME rate")
        shadowOf(Looper.getMainLooper()).idle()

        // Typing and clearing a query must keep the keyboard's original editor.
        assertSame(editor, box())
        box().setText("")
        shadowOf(Looper.getMainLooper()).idle()
        box().setText("FRAME rate")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(editor, box())
        assertEquals("FRAME rate", box().text.toString())
        assertTrue(box().hasFocus())
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(popup.isShowing)
        val row = descendants(popup.contentView).first {
            it.isClickable && it.contentDescription?.startsWith(screen.getString(R.string.frame_rate) + ", ") == true
        }
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(popup.isShowing)
        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
    }

    @Test fun backClosesAnOpenSearchBeforeLeavingSettings() {
        val screen = openSettings()
        fun box() = descendants(screen.window.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == screen.getString(R.string.settings_search) }
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        box().setText("no such setting zz")
        shadowOf(Looper.getMainLooper()).idle()
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(descendants(popup.contentView).filterIsInstance<TextView>()
            .any { it.text == screen.getString(R.string.settings_search_empty) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertFalse(popup.isShowing)
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun rebuiltSearchFieldsDoNotLeaveLayoutListenersOnTheWindow() {
        val screen = openSettings()
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        fun globalLayoutListeners(): Int {
            val listeners = ReflectionHelpers.getField<Any?>(screen.window.decorView.viewTreeObserver, "mOnGlobalLayoutListeners")
                ?: return 0
            return ReflectionHelpers.callInstanceMethod<Int>(listeners, "size")
        }
        val before = globalLayoutListeners()
        repeat(5) {
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(descendants(screen.window.decorView).any { it is EditText })
        assertEquals(before, globalLayoutListeners())
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp-land")
    fun backCollapsesAnEmptySearchAndFullLayoutKeepsItsExistingLabel() {
        val screen = openSettings()
        val button = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }
        assertTrue(button is android.widget.Button)
        assertEquals(screen.getString(R.string.settings_search_hint), (button as TextView).text.toString())
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(descendants(screen.window.decorView).any { it is EditText && it.hasFocus() })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(descendants(screen.window.decorView).none { it is EditText })
        assertTrue(descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }.hasFocus())
    }

    @Test
    @Config(qualifiers = "en-w500dp-h400dp-land")
    fun compactSearchUsesAnIconTheSameSizeAsAppearanceAndCollapsesAfterBack() {
        val screen = openSettings()
        fun search() = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_search)
        }
        val appearance = descendants(screen.window.decorView).single { it.tag == "app_appearance_button" }
        assertTrue(search() is android.widget.ImageButton)
        assertEquals(appearance.layoutParams.width, search().layoutParams.width)
        assertEquals(appearance.layoutParams.height, search().layoutParams.height)
        search().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(search() is EditText)
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(search() is android.widget.ImageButton)
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test
    @Config(shadows = [HotspotSearchProbe::class])
    fun hotspotSearchIndexesItsAsyncCardWithoutStartingAnAdbProbe() {
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        HotspotSearchProbe.workers.clear()
        HotspotSearchProbe.entered = CountDownLatch(1)
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex")
        val result = index.single { it.title == screen.getString(R.string.auto_car_hotspot_title) }

        assertEquals(SettingsCategory.CONNECTION, result.category)
        assertTrue("Building search metadata must not contact ADB", HotspotSearchProbe.workers.isEmpty())
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.auto_car_hotspot_title) })

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(result.javaClass, result))
        assertTrue(HotspotSearchProbe.entered.await(3, TimeUnit.SECONDS))
        val worker = HotspotSearchProbe.workers.single()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SettingsCategory.CONNECTION,
            ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertEquals(1, descendants(screen.window.decorView).filterIsInstance<SwitchCompat>().count {
            it.contentDescription == screen.getString(R.string.auto_car_hotspot_title)
        })
    }

    @Test
    @Config(shadows = [HotspotSearchProbe::class])
    fun hotspotSearchKeepsTheBydAndCarHotspotAudienceGates() {
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        HotspotSearchProbe.workers.clear()
        HotspotSearchProbe.entered = CountDownLatch(1)
        val screen = openSettings()
        fun index() = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex")
        val title = screen.getString(R.string.auto_car_hotspot_title)

        assertFalse(index().any { it.title == title })
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        shadowOf(context.packageManager).removePackage("com.byd.carsettings")
        assertFalse(index().any { it.title == title })
        assertTrue(HotspotSearchProbe.workers.isEmpty())
    }

    @Test fun cardControlsShareOneHeightAndGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        val density = screen.resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        val buttons = descendants(scroll).filterIsInstance<android.widget.Button>()
            .filter { it !is SwitchCompat && (it.parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL }
            .toList()

        assertTrue(buttons.size >= 4)
        buttons.forEach { button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            assertEquals(button.text.toString(), dp(60), lp.height)
            assertEquals(button.text.toString(), 0, lp.topMargin)
            assertTrue(button.text.toString(), lp.bottomMargin in setOf(0, dp(6), dp(12)))
        }
        val page = scroll.getChildAt(0)
        assertFalse(descendants(scroll).any { it.tag == "spacer" && it.parent !== page })
    }

    @Test fun searchNamesOpenTheirOwnCategoryNotACardThatLinksToIt() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val advanced = index.single { ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.settings_advanced) }

        assertEquals(SettingsCategory.ADVANCED, ReflectionHelpers.getField<SettingsCategory>(advanced, "category"))
    }

    @Test fun aRowBeforeAButtonKeepsTheGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_vehicle)) }
            .performClick()
        val chooseImage = texts(screen).single { it.text == screen.getString(R.string.choose_image) }
        val card = chooseImage.parent as LinearLayout
        val preview = card.getChildAt(card.indexOfChild(chooseImage) - 1)

        assertEquals(Math.round(12 * screen.resources.displayMetrics.density),
            (preview.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun tappingAToggleRowChangesTheSetting() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        (fullScreen.parent as View).performClick()

        assertTrue(fullScreen.isChecked)
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
    }

    @Test fun compactDetailReturnsToOverviewBeforeLeavingSettings() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun openingACompactCategoryAlwaysStartsAtTheTop() {
        val screen = openSettings()
        val firstScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        firstScroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        firstScroll.layout(0, 0, 600, 700)
        firstScroll.scrollTo(0, 400)
        assertTrue(firstScroll.scrollY > 0)

        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        val detailScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        assertEquals(0, detailScroll.scrollY)
    }

    @Test fun quickFullScreenChangesBothExistingPreferencesTogether() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        fullScreen.performClick()

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(true, fullScreen.isChecked)
    }

    @Test fun systemBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun toolbarBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        texts(screen).single { it.text == screen.getString(R.string.back) }.performClick()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
    }

    @Test fun connectionSetupOpenedDirectlyStillReturnsHome() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
        ).setup().get().also { activity = it }

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun advancedContainsOnlyExpertSections() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { candidate ->
                candidate.contentDescription == screen.getString(
                    R.string.settings_open_category,
                    screen.getString(R.string.settings_advanced),
                ) && descendants(candidate).filterIsInstance<TextView>().count() == 2
            }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_advanced_caution_title) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.advanced_vehicle_data) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.audio_routing) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.location) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.diagnostics) })
    }

    @Test fun displayOpensPictureAdjustments() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.picture_adjustments) })
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        val screen = openSettings()
        fun visibleIn(category: Int): List<CharSequence> {
            descendants(screen.window.decorView).first { candidate ->
                candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
            }.performClick()
            return texts(screen).map { it.text }.toList().also { screen.onBackPressedDispatcher.onBackPressed() }
        }
        fun text(id: Int) = screen.getString(id)

        val display = visibleIn(R.string.settings_display)
        val audio = visibleIn(R.string.audio)
        val vehicle = visibleIn(R.string.settings_vehicle)
        val advanced = visibleIn(R.string.settings_advanced)
        val connection = visibleIn(R.string.connection)

        // main #583：充电电流档位属于 USB 连接卡，只出现在 Connection，不进 Advanced。
        assertTrue(connection.any { it.startsWith(text(R.string.settings_usb_charging)) })
        assertFalse(advanced.any { it.startsWith(text(R.string.settings_usb_charging)) })
        assertTrue(audio.any { it.startsWith(text(R.string.music_buffer)) })
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.settings_disable_media_key_audio_focus, R.string.contrib_audio_home_toggle_audio_focus).forEach {
            assertTrue(text(it), text(it) in advanced)
            assertFalse(text(it), text(it) in audio)
        }
        assertTrue(text(R.string.right_hand_drive) in vehicle)
        assertTrue(text(R.string.car_button_in_carplay) in vehicle)
        assertTrue(text(R.string.wheel_siri_key) in vehicle)
        assertTrue(text(R.string.settings_wheel_keys) in vehicle)
        assertTrue(text(R.string.side_panel) in advanced)
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.settings_disable_media_key_audio_focus, R.string.right_hand_drive, R.string.car_button_in_carplay,
            R.string.side_panel, R.string.split_screen_areas, R.string.carplay_rotation).forEach {
            assertFalse(text(it), text(it) in display)
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun experimentalCallProcessingIsOptInAndMarksTheActiveSessionForReconnect() {
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(context))
        assertFalse(AirPlayPersistence.loadCallVoiceFilter(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
            listOf(R.string.call_echo_cancellation, R.string.call_voice_filter).forEach { title ->
                PendingReconnect.clear()
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val setting = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
                    .single { it.contentDescription == screen.getString(title) }
                assertFalse(setting.isChecked)
                setting.performClick()

                assertTrue(PendingReconnect.isPending(session))
                assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                assertEquals(null, shadowOf(screen).nextStartedActivity)
            }
            assertTrue(AirPlayPersistence.loadCallEchoCancellation(context))
            assertTrue(AirPlayPersistence.loadCallVoiceFilter(context))
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    // main #583：充电电流选择只保存并在下次 USB 连接生效，不断开运行中的会话（作者：TRAE AI）。
    @Test
    fun usbChargingIsSavedForTheNextWiredConnectionWithoutDroppingTheSession() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            PendingReconnect.clear()
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val row = texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_usb_charging)) }
            assertEquals("${screen.getString(R.string.settings_usb_charging)} · ${screen.getString(R.string.settings_usb_charging_normal)}", row.text.toString())
            row.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            shadowOf(dialog).clickOnItem(UsbChargingCurrent.entries.indexOf(UsbChargingCurrent.LOW))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(UsbChargingCurrent.LOW, AirPlayPersistence.loadUsbChargingCurrent(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    // main #615：关闭媒体键音频焦点为 opt-in,切换时标记活动会话待重连（作者：TRAE AI）。
    @Test
    @Config(sdk = [28, 33])
    fun disablingMediaKeyAudioFocusIsOptInAndMarksTheActiveSessionForReconnect() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
                .single { it.contentDescription == screen.getString(R.string.settings_disable_media_key_audio_focus) }
            assertFalse(setting.isChecked)

            setting.performClick()

            assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    // main #608：MTK 调优开关只在检测到 MTK 解码器时出现，且须标记活动会话待重连。
    @Test
    @Config(sdk = [28])
    fun mtkDecoderTuningAppearsOnlyForMtkAndMarksReconnect() {
        AirPlayPersistence.saveMtkDecoderTuning(context, false)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        screen.selectedMtkDecoderName = { null }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_mtk_decoder_tuning) })

        val decoderName = "OMX.MTK.VIDEO.DECODER.HEVC"
        screen.selectedMtkDecoderName = { decoderName }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertTrue(texts(screen).any {
            it.text == screen.getString(R.string.settings_mtk_decoder_tuning_description, decoderName)
        })

        val session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayBackgroundSession.active = true
        try {
            val setting = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
                .single { it.contentDescription == screen.getString(R.string.settings_mtk_decoder_tuning) }
            assertFalse(setting.isChecked)
            setting.performClick()

            assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
            assertTrue(PendingReconnect.isPending(session))
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            AirPlayPersistence.saveMtkDecoderTuning(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    // main #482：自动让出焦点开关须标记活动会话待重连（作者：TRAE AI）。
    @Test
    @Config(sdk = [28, 33])
    fun theAudioFocusAutoYieldSwitchMarksTheActiveSessionForReconnect() {
        // The sink reads this flag once, when it is built, so the change lands on the next session.
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<SwitchCompat>()
                .single { it.contentDescription == screen.getString(R.string.audio_focus_auto_yield) }
            assertTrue(setting.isChecked)
            setting.performClick()

            assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailStaysOutsideTheScrollingCategory() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val railDestination = descendants(screen.window.decorView).first {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) &&
                descendants(it).filterIsInstance<ImageView>().count() == 1
        }

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) && isInside(it, categoryScroll) })
        assertFalse(isInside(railDestination, categoryScroll))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailUsesAnIconForEveryDestination() {
        val screen = openSettings()
        val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        val destinations = descendants(rail).filter { destination ->
            destination.contentDescription?.startsWith("Open ") == true &&
                destination.contentDescription?.endsWith(" settings") == true &&
                descendants(destination).filterIsInstance<ImageView>().count() == 1
        }.toList()
        assertEquals(10, destinations.size)
        destinations.forEach { destination ->
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h400dp")
    fun selectingTheBottomRailDestinationKeepsItVisibleAndFocusedOnAShortScreen() {
        val screen = openSettings()
        val density = screen.resources.displayMetrics.density
        fun layout() {
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val width = Math.round(1000 * density)
            val height = Math.round(400 * density)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }
        fun rail() = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        fun advanced(scroll: ScrollView) = descendants(scroll).single {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_advanced))
        }
        layout()
        val previousRail = rail()
        previousRail.scrollTo(0, previousRail.getChildAt(0).height)
        assertTrue("This window must require rail scrolling", previousRail.scrollY > 0)
        val destination = advanced(previousRail)
        assertTrue(destination.requestFocus())
        assertTrue(previousRail.hasFocus())

        destination.performClick()
        layout()

        val currentRail = rail()
        val selected = advanced(currentRail)
        assertFalse(previousRail === currentRail)
        assertTrue(selected.isSelected)
        assertTrue("D-pad focus must follow the selected category", selected.isFocused)
        assertTrue(currentRail.scrollY > 0)
        val top = selected.top + currentRail.getChildAt(0).top
        assertTrue("Selected row starts inside the rail viewport", top >= currentRail.scrollY)
        assertTrue("Selected row ends inside the rail viewport",
            top + selected.height <= currentRail.scrollY + currentRail.height)
        assertEquals("Category scrolling remains independent", 0,
            ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
    }

    // main 0.2.18：Overview 的 About/Diagnostics/Advanced 是相连的三张分类卡（作者：TRAE AI）。
    @Test fun overviewUtilitiesFollowAboutAsSeparateCategoryCards() {
        val screen = openSettings()
        fun categoryCard(category: Int) = descendants(screen.window.decorView).single { candidate ->
            candidate.contentDescription == screen.getString(
                R.string.settings_open_category,
                screen.getString(category),
            ) && descendants(candidate).filterIsInstance<TextView>().count() == 2
        }
        val about = categoryCard(R.string.about)
        val diagnostics = categoryCard(R.string.diagnostics)
        val advanced = categoryCard(R.string.settings_advanced)
        assertSame(about.parent, diagnostics.parent)
        assertSame(diagnostics.parent, advanced.parent)
        val categories = about.parent as LinearLayout
        assertEquals(categories.indexOfChild(about) + 1, categories.indexOfChild(diagnostics))
        assertEquals(categories.indexOfChild(diagnostics) + 1, categories.indexOfChild(advanced))
        val gap = Math.round(16 * screen.resources.displayMetrics.density)
        assertEquals(gap, (diagnostics.layoutParams as LinearLayout.LayoutParams).bottomMargin)
        assertEquals(gap, (advanced.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    @Implements(CarHotspotSetup::class, isInAndroidSdk = false)
    class HotspotSearchProbe {
        @Implementation fun check(context: Context, adb: LocalAdb): LocalAdb.Access {
            workers += Thread.currentThread()
            entered.countDown()
            return LocalAdb.Access.READY
        }

        companion object {
            val workers = CopyOnWriteArrayList<Thread>()
            var entered = CountDownLatch(1)
        }
    }

    // main #490：Overview 必须链接到除自身外的每个分类（作者：TRAE AI）。
    @Test fun overviewLinksToEveryOtherCategory() {
        val screen = openSettings()
        (SettingsCategory.entries - SettingsCategory.OVERVIEW).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            assertTrue(category.name, descendants(screen.window.decorView).any {
                it.contentDescription == screen.getString(R.string.settings_open_category, title)
            })
        }
    }

    // main #491：标题栏操作控件（返回/搜索框/外观按钮）共享同一高度（作者：TRAE AI）。
    @Test fun headerActionsShareOneHeight() = assertHeaderActionsShareOneHeight()

    @Test
    @Config(sdk = [29], qualifiers = "en-w400dp-h700dp-port")
    fun compactHeaderActionsShareOneHeight() = assertHeaderActionsShareOneHeight()

    private fun assertHeaderActionsShareOneHeight() {
        val screen = openSettings()
        val appearance = descendants(screen.window.decorView).single { it.tag == "app_appearance_button" }
        val actions = (appearance.parent as ViewGroup).let { header ->
            (0 until header.childCount).map(header::getChildAt)
                .filter { it is android.widget.Button || it is android.widget.ImageButton || it is EditText }
        }
        assertTrue("header action controls: ${
            actions.map { "${it.javaClass.simpleName}=${it.layoutParams.height}" }
        }", actions.size >= 3)
        assertEquals(1, actions.map { it.layoutParams.height }.toSet().size)
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun openConnectionSetupFromSettings(): DiPlayActivity = openSettings().also { screen ->
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.connection)) }
            .performClick()
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view.parent) { it.parent }.any { it === ancestor }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
