package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = DecoderInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, DecoderInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, DecoderInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }

    @Test fun aWaitShorterThanOneDrainStillDrainsAndRetriesOnce() {
        // 音频把等待上限压到 100 ms（#649）；阻塞的轨道写入可能超过这个时长。
        var time = 0L
        var drains = 0
        var dequeues = 0
        assertEquals(-1, DecoderInputPump.acquire(
            running = { true }, drain = { drains++; time += 260 }, dequeue = { dequeues++; -1 },
            nanoTime = { time }, timeoutNs = 100,
        ))
        assertEquals(1, drains)
        assertEquals(1, dequeues)
        time = 0
        assertEquals(4, DecoderInputPump.acquire(
            running = { true }, drain = { time += 260 }, dequeue = { 4 },
            nanoTime = { time }, timeoutNs = 100,
        ))
    }
}
