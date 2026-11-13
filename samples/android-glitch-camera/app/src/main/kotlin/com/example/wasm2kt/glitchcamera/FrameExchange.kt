package com.example.wasm2kt.glitchcamera

import java.util.concurrent.atomic.AtomicReference

/**
 * RGBA frame buffers shared by the conversion stage and the engine stage, so that converting a
 * frame never overwrites the one the engine is reading. Thread-safe.
 *
 * At most three buffers are in use at a time on the camera path (one being converted, one waiting
 * in [LatestFrameSlot], one in the engine), so [capacity] free buffers are enough to stop
 * allocating once the pipeline runs.
 */
class FrameBufferPool(private val capacity: Int = 3) {
    private val free = ArrayList<ByteArray>(capacity)

    /** A buffer of exactly [size] bytes, reused when one is free. Its contents are undefined. */
    @Synchronized
    fun acquire(size: Int): ByteArray {
        // A different size means the processing size changed: the old buffers are not needed again.
        free.removeAll { it.size != size }
        return if (free.isEmpty()) ByteArray(size) else free.removeAt(free.size - 1)
    }

    /** Gives [buffer] back once nothing reads it any more. */
    @Synchronized
    fun release(buffer: ByteArray) {
        if (free.size < capacity && free.none { it === buffer }) free.add(buffer)
    }

    /** Number of free buffers (for tests). */
    @get:Synchronized
    internal val freeCount: Int get() = free.size
}

/**
 * The latest converted frame, waiting for the engine stage. A newer frame replaces one the engine
 * has not taken yet, and the replaced frame's buffer goes back to [pool]. Thread-safe.
 */
class LatestFrameSlot(private val pool: FrameBufferPool) {
    private val pending = AtomicReference<ExtractedFrame?>(null)

    /** Makes [frame] the next one the engine takes. */
    fun offer(frame: ExtractedFrame) {
        pending.getAndSet(frame)?.let { pool.release(it.rgbaBytes) }
    }

    /** The frame waiting, if any; the caller releases its buffer to the pool when done. */
    fun take(): ExtractedFrame? = pending.getAndSet(null)
}
