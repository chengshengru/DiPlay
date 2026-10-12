package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** 波次十 #473/#536/#606：周期消息节流，其他消息照记。 */
class Iap2RxLogTest {
    @Test fun periodicUpdatesAreThinnedAndOtherMessagesKept() {
        val log = Iap2RxLog(first = 2, every = 5)
        val nowPlaying = (1..10).map { log.line(0x5001) }
        assertEquals(
            listOf("iap2 rx=0x5001", "iap2 rx=0x5001", null, null, "iap2 rx=0x5001 count=5", null, null, null, null,
                "iap2 rx=0x5001 count=10"),
            nowPlaying,
        )
        assertEquals(List(10) { "iap2 rx=0x4158" }, (1..10).map { log.line(0x4158) })
        assertEquals("iap2 rx=0x5201", log.line(0x5201))
    }
}
