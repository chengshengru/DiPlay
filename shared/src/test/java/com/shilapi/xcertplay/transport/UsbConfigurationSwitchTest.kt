package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核级 USB 配置切换的决策逻辑:必须优先 `USBDEVFS_SETCONFIGURATION` ioctl(会刷新内核 actconfig),
 * 只有 ioctl 失败/拿不到 fd 时才退回原始控制传输。
 */
class UsbConfigurationSwitchTest {
    @Test
    fun kernelIoctlIsPreferredAndSkipsTheControlTransfer() {
        val events = mutableListOf<String>()
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = 42,
            target = 6,
            kernelDetach = { fd -> events.add("detach:$fd"); 0 },
            kernelSet = { fd, value -> events.add("set:$fd:$value"); 0 },
            controlSet = { events.add("control"); true },
        )

        assertTrue(outcome.usedKernel)
        assertTrue(outcome.selected)
        assertEquals(0, outcome.kernelResult)
        assertEquals(0, outcome.kernelDetach)
        assertNull(outcome.controlResult)
        assertEquals(listOf("detach:42", "set:42:6"), events)
    }

    @Test
    fun kernelFailureFallsBackToTheControlTransfer() {
        val events = mutableListOf<String>()
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = 7,
            target = 6,
            kernelDetach = { events.add("detach"); -16 },
            kernelSet = { _, _ -> events.add("set"); -16 },
            controlSet = { events.add("control"); true },
        )

        assertTrue(outcome.usedKernel)
        assertTrue(outcome.selected)
        assertEquals(-16, outcome.kernelResult)
        assertEquals(true, outcome.controlResult)
        assertEquals(listOf("detach", "set", "control"), events)
    }

    @Test
    fun missingFileDescriptorUsesTheControlTransferWithoutTouchingTheKernel() {
        val events = mutableListOf<String>()
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = -1,
            target = 6,
            kernelDetach = { events.add("detach"); 0 },
            kernelSet = { _, _ -> events.add("set"); 0 },
            controlSet = { events.add("control"); false },
        )

        assertFalse(outcome.usedKernel)
        assertFalse(outcome.selected)
        assertNull(outcome.kernelResult)
        assertNull(outcome.kernelDetach)
        assertEquals(false, outcome.controlResult)
        assertEquals(listOf("control"), events)
    }

    @Test
    fun kernelDiagnosticReportsTargetDescriptorAndResult() {
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = 11,
            target = 6,
            kernelDetach = { 0 },
            kernelSet = { _, _ -> 0 },
            controlSet = { false },
        )

        val line = outcome.diagnostic(target = 6, fileDescriptor = 11, active = 6)
        assertTrue(line, line.contains("kernel SET_CONFIGURATION(6)"))
        assertTrue(line, line.contains("fd=11"))
        assertTrue(line, line.contains("result=0"))
        assertTrue(line, line.contains("active=6"))
        assertTrue(line, line.contains("ok=true"))
    }

    @Test
    fun controlDiagnosticIsUsedWhenTheKernelPathIsSkipped() {
        val outcome = UsbConfigurationSwitch.switch(
            fileDescriptor = -1,
            target = 6,
            kernelDetach = { 0 },
            kernelSet = { _, _ -> 0 },
            controlSet = { true },
        )

        val line = outcome.diagnostic(target = 6, fileDescriptor = -1, active = 5)
        assertTrue(line, line.contains("control SET_CONFIGURATION(6)"))
        assertTrue(line, line.contains("active=5"))
    }
}
