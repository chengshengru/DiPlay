package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 车机 5 GHz companion 热点识别（波次十 #617）。 */
class ManualHotspotCompanionTest {
    @Test fun theCarsFiveGhzNetworkIsTheConfiguredNameWithA5gSuffix() {
        // #617: Android 只认识 "HOTSPOT"；车机同时广播 "HOTSPOT_5G"。
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "HOTSPOT_5G"))
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "Car", expectedSsid = "Car-5G"))
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "Car", expectedSsid = "Car_5g"))
    }

    @Test fun otherNamesStillDoNotMatch() {
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "HOTSPOT"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "OTHER_5G"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT_5G", expectedSsid = "HOTSPOT"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "", expectedSsid = "_5G"))
    }
}
