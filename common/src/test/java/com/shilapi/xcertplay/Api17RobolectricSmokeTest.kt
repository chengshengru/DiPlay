package com.shilapi.xcertplay

import android.Manifest
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验证 Robolectric 4.10 可以在 JVM 上模拟 Android 4.2.2（API 17 / SDK 17），
 * 供 android-4.2.2 分支在无真机环境下做框架级单元测试。
 *
 * 用 @Config(sdk = [17]) 固定模拟级别，并在真实 SDK 环境下复验
 * WirelessPermissions 的 API 17 守卫（不得出现 31+ 才有的新权限）。
 *
 * @author TRAE AI
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [17], manifest = Config.NONE)
class Api17RobolectricSmokeTest {

    /** 模拟环境必须是 API 17，否则说明 Robolectric 版本不再支持 SDK 17。 */
    @Test fun robolectricEmulatesAndroid422() {
        assertEquals(17, Build.VERSION.SDK_INT)
        assertEquals("4.2.2", Build.VERSION.RELEASE)
    }

    /** API 17 环境下应能拿到可用的 Application 上下文。 */
    @Test fun applicationContextIsAvailableOnApi17() {
        val context = RuntimeEnvironment.getApplication()
        assertNotNull(context.packageName)
    }

    /** 用真实 Build.VERSION.SDK_INT（17）驱动 WirelessPermissions，验证 API 17 守卫。 */
    @Test fun wirelessPermissionsGuardHoldsOnRealEmulatedSdk() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, Build.VERSION.SDK_INT),
        )
        assertEquals(
            emptyList<String>(),
            WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, Build.VERSION.SDK_INT),
        )
    }
}
