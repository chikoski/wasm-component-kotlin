package com.example.wasm2kt.glitchcamera

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.CpuSelfieSegmenterGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * [FrameSegmenter] backed by MediaPipe Tasks `selfie_segmenter.tflite` running strictly on CPU via
 * [CpuSelfieSegmenterGraph]. Lazily initializes the graph on the first segmentation call and
 * reuses direct input and quantized output buffers across frames.
 */
class MediaPipeFrameSegmenter(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
) : FrameSegmenter {
    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScheduled = AtomicBoolean(false)

    @Volatile
    private var closed = false
    @Volatile
    private var closeCleanupJob: Job? = null

    private var cpuGraph: CpuSelfieSegmenterGraph? = null
    private var initializationFailed = false
    private var consecutiveInferenceFailures = 0
    private var failureLogged = false
    private var directInputBuffer: ByteBuffer? = null
    private var maskWeightsBuffer: ByteArray? = null

    override fun segment(width: Int, height: Int, rgbaBytes: ByteArray): ForegroundMask? {
        if (closed || width <= 0 || height <= 0) {
            return null
        }
        val expectedBytesLong = width.toLong() * height.toLong() * 4L
        if (expectedBytesLong > Int.MAX_VALUE.toLong() || rgbaBytes.size.toLong() != expectedBytesLong) {
            return null
        }
        lock.lock()
        try {
            if (closed) return null
            val graph = ensureSegmenterLocked() ?: return null
            val inputBuffer = acquireInputBufferLocked(rgbaBytes.size)
            inputBuffer.clear()
            inputBuffer.put(rgbaBytes)
            inputBuffer.flip()
            val inputImage = ByteBufferImageBuilder(
                inputBuffer,
                width,
                height,
                MPImage.IMAGE_FORMAT_RGBA,
            ).build()
            try {
                val floats = FrameTrace.section(FrameTrace.SEGMENT) {
                    graph.segment(inputImage)
                } ?: return null
                if (floats.size != MASK_PIXEL_COUNT) {
                    if (!failureLogged) {
                        failureLogged = true
                        Log.w(
                            TAG,
                            "Unexpected selfie segmenter output size ${floats.size} (expected $MASK_PIXEL_COUNT)",
                        )
                    }
                    return null
                }
                consecutiveInferenceFailures = 0
                failureLogged = false
                val reuse = maskWeightsBuffer ?: ByteArray(MASK_PIXEL_COUNT).also { maskWeightsBuffer = it }
                return FrameMaskCompositor.quantizeConfidenceFloatArray(
                    floats = floats,
                    maskWidth = MASK_DIMENSION,
                    maskHeight = MASK_DIMENSION,
                    reuseBuffer = reuse,
                    applySigmoid = graph.requiresSigmoid,
                )
            } finally {
                runCatching { inputImage.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleInferenceFailureLocked(e)
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun handleInferenceFailureLocked(e: Exception) {
        val failedGraph = cpuGraph
        cpuGraph = null
        if (failedGraph != null) {
            runCatching { failedGraph.close() }
        }
        consecutiveInferenceFailures++
        if (!failureLogged) {
            failureLogged = true
            val detail = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            Log.w(TAG, "Segmentation inference failed: $detail")
        }
        if (consecutiveInferenceFailures > MAX_RECOVERY_ATTEMPTS) {
            initializationFailed = true
        }
    }

    private fun ensureSegmenterLocked(): CpuSelfieSegmenterGraph? {
        cpuGraph?.let { return it }
        if (initializationFailed) return null
        return try {
            CpuSelfieSegmenterGraph.create(appContext, modelAssetPath).also {
                cpuGraph = it
            }
        } catch (e: LinkageError) {
            initializationFailed = true
            val detail = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            Log.w(TAG, "MediaPipe native library unavailable on this ABI: $detail", e)
            null
        } catch (e: Exception) {
            initializationFailed = true
            val detail = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            Log.w(TAG, "Segmentation initialization failed: $detail", e)
            null
        }
    }

    private fun acquireInputBufferLocked(byteCount: Int): ByteBuffer {
        val existing = directInputBuffer
        if (existing != null && existing.capacity() == byteCount) {
            return existing
        }
        val created = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        directInputBuffer = created
        return created
    }

    override fun close() {
        closed = true
        if (lock.tryLock()) {
            try {
                closeResourcesLocked()
            } finally {
                lock.unlock()
            }
            return
        }
        if (cleanupScheduled.compareAndSet(false, true)) {
            closeCleanupJob = closeScope.launch {
                lock.lock()
                try {
                    closeResourcesLocked()
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    private fun closeResourcesLocked() {
        try {
            cpuGraph?.let { runCatching { it.close() } }
        } finally {
            cpuGraph = null
            directInputBuffer = null
            maskWeightsBuffer = null
        }
    }

    companion object {
        private const val TAG = "MediaPipeFrameSegmenter"
        const val DEFAULT_MODEL_ASSET_PATH = "selfie_segmenter.tflite"
        private const val MASK_DIMENSION = 256
        private const val MASK_PIXEL_COUNT = MASK_DIMENSION * MASK_DIMENSION
        private const val MAX_RECOVERY_ATTEMPTS = 1

        internal fun selectForegroundMaskIndex(labels: List<String>, maskCount: Int): Int {
            if (maskCount <= 1) return 0
            val explicitFg = labels.indexOfFirst { label ->
                label.equals("person", ignoreCase = true) ||
                    label.equals("selfie", ignoreCase = true) ||
                    label.equals("foreground", ignoreCase = true)
            }
            if (explicitFg in 0 until maskCount) return explicitFg
            return 1
        }
    }
}
