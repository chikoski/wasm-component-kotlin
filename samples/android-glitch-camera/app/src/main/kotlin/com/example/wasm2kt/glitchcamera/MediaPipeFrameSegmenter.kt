package com.example.wasm2kt.glitchcamera

import android.content.Context
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
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
 * [FrameSegmenter] backed by MediaPipe Tasks Vision [ImageSegmenter] and `selfie_segmenter.tflite`.
 * Lazily loads the model on the first inference call and reuses a direct [ByteBuffer] across
 * frames to avoid per-frame native bitmap allocation and recycling overhead.
 */
class MediaPipeFrameSegmenter(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val onUnavailable: ((String) -> Unit)? = null,
) : FrameSegmenter {
    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScheduled = AtomicBoolean(false)

    @Volatile
    private var closed = false
    @Volatile
    private var closeCleanupJob: Job? = null

    private var imageSegmenter: ImageSegmenter? = null
    private var initializationFailed = false
    private var unavailableNotified = false
    private var directInputBuffer: ByteBuffer? = null

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
            val segmenter = ensureSegmenterLocked() ?: return null
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
                val result = FrameTrace.section(FrameTrace.SEGMENT) {
                    segmenter.segment(inputImage)
                }
                val masksOptional = result.confidenceMasks()
                val masks = if (masksOptional.isPresent) masksOptional.get() else emptyList()
                try {
                    if (masks.isEmpty()) return null
                    val fgIndex = selectForegroundMaskIndex(segmenter.labels, masks.size)
                    val fgMaskImage = masks[fgIndex]
                    val floatBuffer = ByteBufferExtractor.extract(fgMaskImage)
                        .duplicate()
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                    return FrameMaskCompositor.quantizeConfidenceFloats(
                        floats = floatBuffer,
                        maskWidth = fgMaskImage.width,
                        maskHeight = fgMaskImage.height,
                    )
                } finally {
                    for (maskImage in masks) {
                        runCatching { maskImage.close() }
                    }
                    result.categoryMask().ifPresent { category ->
                        runCatching { category.close() }
                    }
                }
            } finally {
                runCatching { inputImage.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun ensureSegmenterLocked(): ImageSegmenter? {
        imageSegmenter?.let { return it }
        if (initializationFailed) return null
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            ImageSegmenter.createFromOptions(appContext, options).also {
                imageSegmenter = it
            }
        } catch (e: LinkageError) {
            initializationFailed = true
            notifyUnavailableOnce(e.message ?: "MediaPipe native library unavailable on this ABI")
            null
        } catch (e: Exception) {
            initializationFailed = true
            notifyUnavailableOnce(e.message ?: "Segmentation initialization failed")
            null
        }
    }

    private fun notifyUnavailableOnce(detail: String) {
        if (!unavailableNotified) {
            unavailableNotified = true
            onUnavailable?.invoke("セグメンテーションを利用できないため全体表示になります: $detail")
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
            imageSegmenter?.let { runCatching { it.close() } }
        } finally {
            imageSegmenter = null
            directInputBuffer = null
        }
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "selfie_segmenter.tflite"

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
