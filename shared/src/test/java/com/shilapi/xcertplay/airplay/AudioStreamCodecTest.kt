package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 波次十 #473：Opus 恒以 48 kHz 解码，但 RTP 时钟必须按协商速率（16/24 kHz）计数。 */
class AudioStreamCodecTest {
    @Test fun opusDecodesAt48kHzButKeepsTheNegotiatedRtpClock() {
        // #473: audioFormat 0x10000000 的通话按 16 kHz 计数 RTP 时间戳。
        for ((bits, clock) in listOf(0x10000000L to 16_000, 0x20000000L to 24_000, 0x40000000L to 48_000)) {
            val format = AudioStreamCodec.fromFormatBits(bits, 100, "telephony")
            assertEquals(AudioCodecKind.OPUS, format.codec)
            assertEquals(48_000, format.sampleRate)
            assertEquals(clock, format.rtpClockRate)
        }
    }

    @Test fun otherFormatsCountAtTheirSampleRate() {
        assertEquals(48_000, AudioStreamCodec.fromFormatBits(0x800000L, 102).rtpClockRate)
        assertEquals(44_100, AudioStreamCodec.fromFormatBits(0x400000L, 102).rtpClockRate)
        assertEquals(16_000, AudioStreamCodec.fromFormatBits(0x10L, 100).rtpClockRate)
    }
}
