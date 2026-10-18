package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 超时只是"手机暂时没说话"，不是会话死亡。
 *
 * 旧实现超时后 cancel()+close()：cancel 被厂商内核拒绝时会释放仍排在 usbfs 里的 URB/native 缓冲，
 * 内核完成回调随后写入已释放内存，真机表现是整个进程被 SIGSEGV 打死（C140A 上表现为应用直接退回
 * DiPlay 主页、会话日志戛然而止）；cancel 成功的路径又把正常静默升级成会话死刑。
 * 现在超时保留那条请求，下一次读取继续等它，字节一到达就原样交回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class UsbmuxTimedOutReadTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun aReadThatTimesOutKeepsItsBytesForTheNextCall() {
        UsbQueueReplay.timeouts = 1
        UsbQueueReplay.cancelResult = false
        val pipe = pipe()
        try {
            assertNull(pipe.read(100))
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            // 既不取消也不重新排队：内核里那条 URB 从头到尾只有一条。
            assertEquals(0, UsbQueueReplay.cancels)
            assertEquals(listOf(65_536), UsbQueueReplay.sizes)
        } finally { pipe.close() }
    }

    @Test fun repeatedTimeoutsKeepTheSessionAliveAndReuseTheQueuedRequest() {
        UsbQueueReplay.timeouts = 3
        // 厂商内核拒绝 cancel 的现场：旧实现会在这里断掉整个会话。
        UsbQueueReplay.cancelResult = false
        val pipe = pipe()
        try {
            repeat(3) { assertNull(pipe.read(100)) }
            assertEquals(0, UsbQueueReplay.cancels)
            assertEquals(listOf(65_536), UsbQueueReplay.sizes)
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
        } finally { pipe.close() }
    }

    @Test fun anIdlePipeRepeatedlyTimesOutWithoutFailingOrClosingItsRequest() {
        UsbQueueReplay.timeout = true
        val pipe = pipe()
        try {
            repeat(5) { assertNull(pipe.read(50)) }
            assertEquals(listOf(65_536), UsbQueueReplay.sizes)
            assertEquals(0, UsbQueueReplay.requestCloses)
        } finally { pipe.close() }
        // 只有会话关闭时才释放请求对象，且只释放一次。
        assertEquals(1, UsbQueueReplay.requestCloses)
    }

    private fun pipe() = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
