package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class WiredPermissionsTest {
    // compileSdk 36 的 Manifest 无 ACCESS_LOCAL_NETWORK 常量（API 37 起才有），故用字面量，
    // 与 WiredPermissions 的实现保持一致。
    private val localNetwork = "android.permission.ACCESS_LOCAL_NETWORK"

    @Test fun wiredStartsRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(listOf(localNetwork), WiredPermissions.required(37))
        assertEquals(emptyList<String>(), WiredPermissions.required(36))
        assertEquals(emptyList<String>(), WiredPermissions.required(25))
    }
}
