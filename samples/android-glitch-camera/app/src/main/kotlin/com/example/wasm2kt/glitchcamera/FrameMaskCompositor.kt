package com.example.wasm2kt.glitchcamera

import java.nio.FloatBuffer
import kotlin.math.exp

enum class EffectTargetPass(val label: String) {
    ALL("全体"),
    FOREGROUND("前景"),
    BACKGROUND("背景"),
    NONE("なし"),
    ;

    val requiresSegmentation: Boolean
        get() = this == FOREGROUND || this == BACKGROUND

    val requiresGlitchEngine: Boolean
        get() = this != NONE

    companion object {
        val DEFAULT: EffectTargetPass = ALL
    }
}

enum class SegmentationStatus {
    /** Pass does not use segmentation (ALL or NONE). */
    INACTIVE,
    /** Segmentation is active and a person subject is detected in the frame. */
    DETECTED,
    /** Segmentation is active, functioning normally, and no person is detected in the frame. */
    NO_SUBJECT,
    /** Segmentation failed to initialize or could not produce a mask when required. */
    UNAVAILABLE,
}

/**
 * Foreground confidence per pixel quantized to 0..255 (`0` = background, `255` = foreground).
 */
class ForegroundMask(
    val width: Int,
    val height: Int,
    val weights: ByteArray,
    val hasForegroundSubject: Boolean = FrameMaskCompositor.computeHasForegroundSubject(weights),
) {
    init {
        require(width > 0 && height > 0) { "Invalid mask dimensions ${width}x$height" }
        val countLong = width.toLong() * height.toLong()
        require(countLong <= Int.MAX_VALUE.toLong()) { "Mask dimensions overflow Int: ${width}x$height" }
        require(weights.size.toLong() == countLong) {
            "Expected $countLong mask weights for ${width}x$height, got ${weights.size}"
        }
    }
}

fun interface FrameSegmenter : AutoCloseable {
    fun segment(width: Int, height: Int, rgbaBytes: ByteArray): ForegroundMask?

    override fun close() {}
}

/**
 * Pure-Kotlin soft-blend compositor and segmentation status state helper for 4-pass
 * foreground/background effect switching. Kept free of Android types so it can be tested
 * directly on the host JVM.
 */
object FrameMaskCompositor {
    private const val SUBJECT_CONFIDENCE_THRESHOLD_WEIGHT = 128
    private const val SUBJECT_COVERAGE_PER_MILLE = 15

    private class ColumnLookupEntry(
        val frameWidth: Int,
        val maskWidth: Int,
        val columns: IntArray,
    )

    private val columnLookupCache = ThreadLocal<ColumnLookupEntry?>()

    private fun acquireColumnLookup(frameWidth: Int, maskWidth: Int): IntArray {
        val existing = columnLookupCache.get()
        if (existing != null && existing.frameWidth == frameWidth && existing.maskWidth == maskWidth) {
            return existing.columns
        }
        val columns = IntArray(frameWidth) { x -> ((x.toLong() * maskWidth) / frameWidth).toInt() }
        columnLookupCache.set(ColumnLookupEntry(frameWidth, maskWidth, columns))
        return columns
    }

    internal fun computeHasForegroundSubject(weights: ByteArray): Boolean {
        if (weights.isEmpty()) return false
        val thresholdCount = subjectThresholdCount(weights.size)
        var foregroundPixels = 0
        for (weight in weights) {
            if ((weight.toInt() and 0xFF) >= SUBJECT_CONFIDENCE_THRESHOLD_WEIGHT) {
                foregroundPixels++
                if (foregroundPixels >= thresholdCount) {
                    return true
                }
            }
        }
        return false
    }

    private fun subjectThresholdCount(pixelCount: Int): Int =
        maxOf(1, ((pixelCount.toLong() * SUBJECT_COVERAGE_PER_MILLE) / 1000L).toInt())

    fun quantizeConfidenceFloats(
        floats: FloatBuffer,
        maskWidth: Int,
        maskHeight: Int,
        reuseBuffer: ByteArray? = null,
    ): ForegroundMask {
        val basePos = floats.position()
        return quantizeFloats(
            maskWidth = maskWidth,
            maskHeight = maskHeight,
            availableFloats = floats.remaining(),
            reuseBuffer = reuseBuffer,
        ) { i ->
            floats.get(basePos + i)
        }
    }

    fun quantizeConfidenceFloatArray(
        floats: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        reuseBuffer: ByteArray? = null,
        applySigmoid: Boolean = false,
    ): ForegroundMask = quantizeFloats(
        maskWidth = maskWidth,
        maskHeight = maskHeight,
        availableFloats = floats.size,
        reuseBuffer = reuseBuffer,
        applySigmoid = applySigmoid,
    ) { i ->
        floats[i]
    }

    private inline fun quantizeFloats(
        maskWidth: Int,
        maskHeight: Int,
        availableFloats: Int,
        reuseBuffer: ByteArray?,
        applySigmoid: Boolean = false,
        readFloat: (Int) -> Float,
    ): ForegroundMask {
        require(maskWidth > 0 && maskHeight > 0) { "Invalid mask dimensions ${maskWidth}x$maskHeight" }
        val countLong = maskWidth.toLong() * maskHeight.toLong()
        require(countLong <= Int.MAX_VALUE.toLong()) { "Mask dimensions overflow Int: ${maskWidth}x$maskHeight" }
        val count = countLong.toInt()
        require(availableFloats >= count) {
            "Expected at least $count mask floats, got $availableFloats"
        }
        val weights = if (reuseBuffer != null && reuseBuffer.size == count) {
            reuseBuffer
        } else {
            ByteArray(count)
        }
        var foregroundPixels = 0
        for (i in 0 until count) {
            val raw = readFloat(i)
            val prob = when {
                raw.isNaN() -> 0f
                applySigmoid -> (1.0 / (1.0 + exp(-raw.toDouble()))).toFloat()
                else -> raw
            }
            val clamped = prob.coerceIn(0f, 1f)
            val quantized = (clamped * 255f + 0.5f).toInt()
            if (quantized >= SUBJECT_CONFIDENCE_THRESHOLD_WEIGHT) {
                foregroundPixels++
            }
            weights[i] = quantized.toByte()
        }
        val hasSubject = foregroundPixels >= subjectThresholdCount(count)
        return ForegroundMask(maskWidth, maskHeight, weights, hasSubject)
    }

    fun copyOpaqueRgba(source: ByteArray, dest: ByteArray? = null): ByteArray {
        require(source.size % 4 == 0) { "Expected RGBA byte length multiple of 4, got ${source.size}" }
        val out = if (dest != null && dest.size == source.size) dest else ByteArray(source.size)
        System.arraycopy(source, 0, out, 0, source.size)
        for (i in 3 until out.size step 4) {
            out[i] = 0xFF.toByte()
        }
        return out
    }

    fun blendInPlace(
        originalRgba: ByteArray,
        glitchedRgba: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        mask: ForegroundMask,
        pass: EffectTargetPass,
    ) {
        require(frameWidth > 0 && frameHeight > 0) { "Invalid frame dimensions ${frameWidth}x$frameHeight" }
        val expectedBytesLong = frameWidth.toLong() * frameHeight.toLong() * 4L
        require(expectedBytesLong <= Int.MAX_VALUE.toLong()) {
            "Frame dimensions overflow Int: ${frameWidth}x$frameHeight"
        }
        require(originalRgba.size.toLong() == expectedBytesLong) {
            "Expected $expectedBytesLong original RGBA bytes for ${frameWidth}x$frameHeight, got ${originalRgba.size}"
        }
        require(glitchedRgba.size.toLong() == expectedBytesLong) {
            "Expected $expectedBytesLong glitched RGBA bytes for ${frameWidth}x$frameHeight, got ${glitchedRgba.size}"
        }
        val expectedBytes = expectedBytesLong.toInt()
        when (pass) {
            EffectTargetPass.ALL -> {
                for (i in 3 until expectedBytes step 4) {
                    glitchedRgba[i] = 0xFF.toByte()
                }
            }
            EffectTargetPass.NONE -> {
                copyOpaqueRgba(originalRgba, glitchedRgba)
            }
            EffectTargetPass.FOREGROUND, EffectTargetPass.BACKGROUND -> {
                val applyToForeground = pass == EffectTargetPass.FOREGROUND
                val maskWeights = mask.weights
                if (mask.width == frameWidth && mask.height == frameHeight) {
                    val pixelCount = frameWidth * frameHeight
                    var offset = 0
                    for (p in 0 until pixelCount) {
                        val fgWeight = maskWeights[p].toInt() and 0xFF
                        blendPixel(originalRgba, glitchedRgba, offset, fgWeight, applyToForeground)
                        offset += 4
                    }
                } else {
                    val colOffsets = acquireColumnLookup(frameWidth, mask.width)
                    var offset = 0
                    for (y in 0 until frameHeight) {
                        val maskRow = ((y.toLong() * mask.height) / frameHeight).toInt() * mask.width
                        for (x in 0 until frameWidth) {
                            val fgWeight = maskWeights[maskRow + colOffsets[x]].toInt() and 0xFF
                            blendPixel(originalRgba, glitchedRgba, offset, fgWeight, applyToForeground)
                            offset += 4
                        }
                    }
                }
            }
        }
    }

    private fun blendPixel(
        originalRgba: ByteArray,
        glitchedRgba: ByteArray,
        offset: Int,
        fgWeight: Int,
        applyToForeground: Boolean,
    ) {
        val glitchWeight = if (applyToForeground) fgWeight else 255 - fgWeight
        when (glitchWeight) {
            255 -> {
                glitchedRgba[offset + 3] = 0xFF.toByte()
            }
            0 -> {
                glitchedRgba[offset] = originalRgba[offset]
                glitchedRgba[offset + 1] = originalRgba[offset + 1]
                glitchedRgba[offset + 2] = originalRgba[offset + 2]
                glitchedRgba[offset + 3] = 0xFF.toByte()
            }
            else -> {
                val origWeight = 255 - glitchWeight
                val origR = originalRgba[offset].toInt() and 0xFF
                val origG = originalRgba[offset + 1].toInt() and 0xFF
                val origB = originalRgba[offset + 2].toInt() and 0xFF
                val glitchR = glitchedRgba[offset].toInt() and 0xFF
                val glitchG = glitchedRgba[offset + 1].toInt() and 0xFF
                val glitchB = glitchedRgba[offset + 2].toInt() and 0xFF
                glitchedRgba[offset] = ((origR * origWeight + glitchR * glitchWeight + 127) / 255).toByte()
                glitchedRgba[offset + 1] = ((origG * origWeight + glitchG * glitchWeight + 127) / 255).toByte()
                glitchedRgba[offset + 2] = ((origB * origWeight + glitchB * glitchWeight + 127) / 255).toByte()
                glitchedRgba[offset + 3] = 0xFF.toByte()
            }
        }
    }
}

/**
 * Coordinates segmentation status transitions for UI display: ignores stale frames arriving after
 * switching to a non-segmented pass, applies an initial reading immediately on entering a
 * segmented pass, and debounces `DETECTED` <-> `NO_SUBJECT` flips over [hysteresisFrames]
 * consecutive frames so edge-of-frame flicker does not recompose the UI.
 */
class SegmentationStatusTracker(
    private val hysteresisFrames: Int = DEFAULT_HYSTERESIS_FRAMES,
) {
    private val lock = Any()
    private var awaitingInitialSample = false
    private var pendingSubjectStatus: SegmentationStatus? = null
    private var pendingSubjectCount: Int = 0

    init {
        require(hysteresisFrames >= 1) { "hysteresisFrames must be >= 1, got $hysteresisFrames" }
    }

    fun resolveForPassSelection(
        currentStatus: SegmentationStatus,
        targetPass: EffectTargetPass,
    ): SegmentationStatus = synchronized(lock) {
        pendingSubjectStatus = null
        pendingSubjectCount = 0
        if (!targetPass.requiresSegmentation) {
            awaitingInitialSample = false
            SegmentationStatus.INACTIVE
        } else if (currentStatus == SegmentationStatus.INACTIVE) {
            awaitingInitialSample = true
            SegmentationStatus.NO_SUBJECT
        } else {
            currentStatus
        }
    }

    fun resolveForFrameOutput(
        activePass: EffectTargetPass,
        currentStatus: SegmentationStatus,
        reportedStatus: SegmentationStatus,
    ): SegmentationStatus = synchronized(lock) {
        if (!activePass.requiresSegmentation) {
            awaitingInitialSample = false
            pendingSubjectStatus = null
            pendingSubjectCount = 0
            return SegmentationStatus.INACTIVE
        }
        if (reportedStatus == SegmentationStatus.INACTIVE) {
            return currentStatus
        }
        if (awaitingInitialSample) {
            awaitingInitialSample = false
            pendingSubjectStatus = null
            pendingSubjectCount = 0
            return reportedStatus
        }
        val isSubjectFlip = (currentStatus == SegmentationStatus.DETECTED &&
            reportedStatus == SegmentationStatus.NO_SUBJECT) ||
            (currentStatus == SegmentationStatus.NO_SUBJECT &&
                reportedStatus == SegmentationStatus.DETECTED)
        if (!isSubjectFlip) {
            pendingSubjectStatus = null
            pendingSubjectCount = 0
            return reportedStatus
        }
        if (pendingSubjectStatus == reportedStatus) {
            pendingSubjectCount++
        } else {
            pendingSubjectStatus = reportedStatus
            pendingSubjectCount = 1
        }
        if (pendingSubjectCount >= hysteresisFrames) {
            pendingSubjectStatus = null
            pendingSubjectCount = 0
            reportedStatus
        } else {
            currentStatus
        }
    }

    companion object {
        const val DEFAULT_HYSTERESIS_FRAMES = 3

        fun shouldClearStatusMessage(
            currentMessage: String?,
            expectedMessage: String?,
        ): Boolean = expectedMessage == null || currentMessage == expectedMessage
    }
}
