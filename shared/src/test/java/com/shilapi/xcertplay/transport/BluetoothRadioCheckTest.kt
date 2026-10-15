package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 波次十一批次A（main #677）：无线启动前蓝牙可用性前置判断。 */
class BluetoothRadioCheckTest {
    @Test fun aRadioThatIsOnLetsTheAttemptGoOn() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = true, pausedByDiPlay = false))
    }

    @Test fun aRadioThatIsOffStopsTheAttemptBeforeTheHotspot() {
        assertEquals(
            BluetoothRadioCheck.RADIO_OFF,
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = false),
        )
    }

    @Test fun aRadioThatDiPlayPausedDoesNotStopTheAttempt() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = true))
    }

    @Test fun aHeadUnitWithoutAnAdapterStopsTheAttemptWhateverElseIsKnown() {
        for (paused in listOf(false, true)) {
            assertEquals(
                BluetoothRadioCheck.NO_ADAPTER,
                BluetoothRadioCheck.blocker(adapterPresent = false, enabled = false, pausedByDiPlay = paused),
            )
        }
    }

    // main #799（波次十三批次E）：重试变不出蓝牙适配器；过去它被无限重试成"正在连接 iPhone"。
    @Test fun aMissingAdapterStopsTheAttemptsWithItsCause() {
        val failure = BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER)
        assertTrue(failure is WirelessStartupException)
        assertEquals(WirelessStartupFailure.BLUETOOTH_UNUSABLE, (failure as WirelessStartupException).reason)
        assertFalse(failure.reason.retryable)
        assertEquals(BluetoothRadioCheck.NO_ADAPTER, failure.message)
        assertTrue(BluetoothRadioCheck.NO_ADAPTER.startsWith("Bluetooth adapter is unavailable"))
        assertTrue(BluetoothRadioCheck.NO_ADAPTER.contains(BluetoothLinkCheck.NO_SOCKETS_MARK))
    }

    // 驾驶员可以把开关打开，所以该尝试继续重试。
    @Test fun aRadioThatIsOffIsStillRetried() {
        val failure = BluetoothRadioCheck.failure(BluetoothRadioCheck.RADIO_OFF)
        assertFalse(failure is WirelessStartupException)
        assertEquals(BluetoothRadioCheck.RADIO_OFF, failure.message)
    }

    /** 宿主屏按这些词匹配本地化提示。 */
    @Test fun theRequestCarriesTheWordsTheHostScreenLooksFor() {
        assertTrue(BluetoothRadioCheck.RADIO_OFF.contains(BluetoothRadioCheck.RADIO_OFF_MARK))
    }
}
