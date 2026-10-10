package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
        // main #401：精确零判定在"回弹"场景下永远不再满足（残余电平高于 0），
        // 导致每次饿死窗口反复 underrun 而恢复流程不再触发；降到启动阈值一半即视为排空。
        isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
            queuedBytes(rawHead) <= floorBytes
}
