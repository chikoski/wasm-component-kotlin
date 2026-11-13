package com.example.wasm2kt.glitchcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FrameExchangeTest {
    @Test
    fun poolReusesReleasedBuffersOfTheSameSize() {
        val pool = FrameBufferPool()
        val a = pool.acquire(16)
        val b = pool.acquire(16)
        assertNotSame(a, b)
        pool.release(a)
        assertSame(a, pool.acquire(16))
    }

    @Test
    fun poolDropsBuffersOfAnotherSize() {
        val pool = FrameBufferPool()
        pool.release(pool.acquire(16))
        val c = pool.acquire(32)
        assertEquals(32, c.size)
        assertEquals(0, pool.freeCount)
    }

    @Test
    fun poolKeepsAtMostItsCapacityAndNoDuplicates() {
        val pool = FrameBufferPool(capacity = 2)
        val buffers = List(3) { pool.acquire(8) }
        buffers.forEach(pool::release)
        assertEquals(2, pool.freeCount)
        pool.release(buffers[0])
        pool.release(buffers[0])
        assertEquals(2, pool.freeCount)
    }

    @Test
    fun slotKeepsOnlyTheLatestFrameAndReleasesTheReplacedOne() {
        val pool = FrameBufferPool()
        val slot = LatestFrameSlot(pool)
        val first = ExtractedFrame(2, 2, pool.acquire(16))
        val second = ExtractedFrame(2, 2, pool.acquire(16))
        slot.offer(first)
        slot.offer(second)
        assertSame(second, slot.take())
        assertNull(slot.take())
        // The replaced frame's buffer is free again.
        assertSame(first.rgbaBytes, pool.acquire(16))
    }

    @Test
    fun conversionWritesIntoThePooledBuffer() {
        val pool = FrameBufferPool()
        val buffer = pool.acquire(2 * 3 * 4)
        pool.release(buffer)
        val source = java.nio.ByteBuffer.allocate(3 * 2 * 4)
        val frame = RgbaFrameTransform.convert(
            source = source,
            srcWidth = 3,
            srcHeight = 2,
            rowStride = 12,
            pixelStride = 4,
            rotationDegrees = 90,
            mirrorHorizontally = false,
            maxLongSide = 100,
            maxShortSide = 100,
            scratch = RgbaBufferHolder(),
            outputBuffer = pool::acquire,
        )
        assertSame(buffer, frame.rgbaBytes)
    }
}
