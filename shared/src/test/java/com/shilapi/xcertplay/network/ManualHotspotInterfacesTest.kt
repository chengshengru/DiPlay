package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * android-4.2.2（API 17）回归：[ManualHotspotInterfaces.sample] 以 `getActiveNetwork()`
 * 前后两读的一致性作为「采样中途网络未变」的判据，但该 API 是 **API 23+**（仓内
 * `NetworkCompat.isConnected` 即以 `ApiLevel.M` 守卫同款调用）。API 17 上调用抛
 * `NoSuchMethodError` 被 `runCatching` 吞掉 → `consistent` 恒为 `false` → 车载热点采样
 * 永远被拒（真机日志：`hotspot sample rejected: network_changed=true apEnabled=true`）。
 *
 * 判据语义必须是「未观测到变化」，而不是「观测不到即算变化」。API 23+ 的否决行为由
 * `ManualHotspotReadinessTest` 的纯逻辑用例（consistent=false → 拒绝）覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ManualHotspotInterfacesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test @Config(sdk = [17]) fun api17KeepsSamplesConsistentWithoutTheActiveNetworkApi() {
        val snapshot = ManualHotspotInterfaces(context).use { it.sample() }
        assertTrue(
            "API 17 没有 getActiveNetwork()；观测不到活动网络不能判成网络变化",
            snapshot.consistent,
        )
    }

    @Test @Config(sdk = [17]) fun api17LeavesModernNetworkEvidenceUnknown() {
        val snapshot = ManualHotspotInterfaces(context).use { it.sample() }
        // getAllNetworks() / getLinkProperties(Network) 同为 API 21+：不可观测时为 null，不得伪造。
        assertNull(snapshot.wifiUpstreams)
        assertNull(snapshot.defaultInterface)
    }

    @Test fun onlyModernAndroidCanCompareTheActiveNetwork() {
        assertFalse(activeNetworkComparable(Build.VERSION_CODES.JELLY_BEAN_MR1)) // API 17
        assertFalse(activeNetworkComparable(Build.VERSION_CODES.LOLLIPOP_MR1)) // API 22
        assertTrue(activeNetworkComparable(Build.VERSION_CODES.M)) // API 23
        assertTrue(activeNetworkComparable(Build.VERSION_CODES.CUR_DEVELOPMENT))
    }
}
