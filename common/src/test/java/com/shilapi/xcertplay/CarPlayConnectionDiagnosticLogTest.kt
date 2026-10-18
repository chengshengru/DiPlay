package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayConnectionDiagnosticLogTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var listener: AirPlaySessionListener
    private val log get() = File(activity.filesDir, "logs/diplay.log").readText()

    @Before fun prepareOldControllerListener() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.javaClass.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(activity)
        activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.set(activity, 2)
        listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1) as AirPlaySessionListener
    }

    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        activity.javaClass.getDeclaredField("sessionLog").apply { isAccessible = true }
            .get(activity).let { (it as Closeable).close() }
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (activity.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun oldTeardownEvidenceSurvivesWithoutTheOldControllerTakingOverTheSession() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 phase=CONTROL teardown end elapsedMs=117 executorTerminated=true")
        listener.onDebugLog("old controller ordinary state")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(log.contains("teardown end elapsedMs=117"))
        // 实时面板上出现的每一行都要落盘，旧控制器的收尾行也不例外；被拒绝的是 UI/会话状态，不是日志。
        assertTrue(log.contains("old controller ordinary state"))
        assertEquals(2, activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.get(activity))
    }

    @Test fun anOpenMenuNoLongerDropsTheLiveLogFromTheReport() {
        activity.javaClass.getDeclaredField("menuOpen").apply { isAccessible = true }.set(activity, true)
        val line = "hotspot sample accepted iface=wlan0 family=IPv4"
        listener.onDebugLog(line)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        // 菜单展开期间只有 UI 更新被推迟，日志仍必须落盘；否则面板有、报告没有。
        assertTrue(log.contains(line))
        assertTrue(usbDiagText().contains(line))
    }

    @Test fun everyLivePanelLineAlsoReachesTheReport() {
        val lines = listOf(
            "Manual hotspot configReadable=true security=WPA_WPA2_PERSONAL iface=wlan0",
            "airplay listener ready port=7000",
            "stage=RunningWireless",
        )
        lines.forEach(listener::onDebugLog)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val file = log
        for (line in lines) {
            assertTrue("面板缺行: $line", usbDiagText().contains(line))
            assertTrue("报告缺行: $line", file.contains(line))
        }
    }

    private fun usbDiagText(): String {
        val deque = activity.javaClass.getDeclaredField("usbDiagLines").apply { isAccessible = true }
            .get(activity) as java.util.ArrayDeque<*>
        return deque.joinToString("\n")
    }

    @Test fun theDiagnosticPrefixDoesNotBypassCredentialRedaction() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 token=private-token")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertFalse(log.contains("private-token"))
    }
}
