package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HotspotModeMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test fun oldLocalSelectionMigratesWithoutLosingCarHotspotDetails() {
        prefs.edit().putString("wireless_hotspot_mode", "LOCAL_ONLY_HOTSPOT").apply()
        AirPlayPersistence.saveManualHotspotSsid(context, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "test-password")
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("MANUAL", prefs.getString("wireless_hotspot_mode", null))
        assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test fun freshInstallUsesBuiltInHotspot() {
        prefs.edit().clear().apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test fun existingWifiDirectSelectionIsPreserved() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    @Test @Config(sdk = [28]) fun olderAndroidKeepsWifiDirectSelected() {
        prefs.edit().putString("wireless_hotspot_mode", "WIFI_P2P").apply()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    /**
     * android-4.2.2：ExistingWifiManager 依赖 API 21 的 ConnectivityManager.NetworkCallback，
     * API 17 设备上连类加载都无法完成（NoClassDefFoundError）。旧设置 / 备份恢复出来的
     * EXISTING_WIFI 必须在加载时自愈为 MANUAL，否则 UI 不呈现该选项、连接时才被拒绝。
     */
    @Test @Config(sdk = [17]) fun existingWifiMigratesToCarHotspotOnApi17() {
        prefs.edit().putString("wireless_hotspot_mode", "EXISTING_WIFI").apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("MANUAL", prefs.getString("wireless_hotspot_mode", null))
    }

    @Test fun existingWifiStaysSelectedOnModernAndroid() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(context))
        assertEquals("EXISTING_WIFI", prefs.getString("wireless_hotspot_mode", null))
    }
}
