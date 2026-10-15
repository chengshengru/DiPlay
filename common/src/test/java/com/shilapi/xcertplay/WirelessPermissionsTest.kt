package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 移植自 main #465。android-36 编译平台没有 Manifest.permission.ACCESS_LOCAL_NETWORK 常量，
 * 测试中直接使用权限名字符串。
 */
class WirelessPermissionsTest {
    private val localNetwork = "android.permission.ACCESS_LOCAL_NETWORK"

    @Test fun hotspotModesRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                localNetwork,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 31),
        )
    }

    @Test fun existingWifiRequestsLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                localNetwork,
            ),
            WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 37),
        )
        assertEquals(emptyList<String>(), WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 29))
    }

    /** API 17 守卫：低版本不得请求任何 31+ 才有的新权限。 */
    @Test fun api17RequestsOnlyLegacyLocationPermissions() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 17),
        )
        assertEquals(emptyList<String>(), WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 17))
    }

    @Test fun aRefusalNamesOnlyTheAccessThatIsMissing() {
        val location = R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        val nearby = R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        val checkPermissions = R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
        assertEquals(location, WirelessPermissions.refusalMessage(listOf(Manifest.permission.ACCESS_FINE_LOCATION)))
        // Android 12 asks for both; Location is named first, Nearby devices once Location is allowed.
        assertEquals(location, WirelessPermissions.refusalMessage(
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION)))
        assertEquals(nearby, WirelessPermissions.refusalMessage(listOf(Manifest.permission.BLUETOOTH_CONNECT)))
        assertEquals(nearby, WirelessPermissions.refusalMessage(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, localNetwork)))
        // Android 17 with Nearby devices allowed: local network access alone is not under Nearby devices.
        assertEquals(checkPermissions, WirelessPermissions.refusalMessage(listOf(localNetwork)))
    }
}
