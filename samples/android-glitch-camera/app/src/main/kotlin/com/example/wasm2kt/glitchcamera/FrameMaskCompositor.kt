package com.example.wasm2kt.glitchcamera

import java.nio.FloatBuffer

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

/**
 * Foreground confidence per pixel quantized to 0..255 (`0` = background, `255` = foreground).
 */
class ForegroundMask(
    val width: Int,
    val height: Int,
    val weights: ByteArray,
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
 * Pure-Kotlin soft-blend compositor for 4-pass foreground/background effect switching.
 * Kept free of Android types so it can be tested directly on the host JVM.
 */
object FrameMaskCompositor {
    fun quantizeConfidenceFloats(
        floats: FloatBuffer,
        maskWidth: Int,
        maskHeight: Int,
        reuseBuffer: ByteArray? = null,
    ): ForegroundMask {
        require(maskWidth > 0 && maskHeight > 0) { "Invalid mask dimensions ${maskWidth}x$maskHeight" }
        val countLong = maskWidth.toLong() * maskHeight.toLong()
        require(countLong <= Int.MAX_VALUE.toLong()) { "Mask dimensions overflow Int: ${maskWidth}x$maskHeight" }
        val count = countLong.toInt()
        require(floats.remaining() >= count) {
            "Expected at least $count mask floats, got ${floats.remaining()}"
        }
        val weights = if (reuseBuffer != null && reuseBuffer.size == count) {
            reuseBuffer
        } else {
            ByteArray(count)
        }
        val basePos = floats.position()
        for (i in 0 until count) {
            val raw = floats.get(basePos + i)
            val clamped = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
            weights[i] = (clamped * 255f + 0.5f).toInt().toByte()
        }
        return ForegroundMask(maskWidth, maskHeight, weights)
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
                    var offset = 0
                    for (y in 0 until frameHeight) {
                        val maskRow = ((y.toLong() * mask.height) / frameHeight).toInt() * mask.width
                        for (x in 0 until frameWidth) {
                            val mx = ((x.toLong() * mask.width) / frameWidth).toInt()
                            val fgWeight = maskWeights[maskRow + mx].toInt() and 0xFF
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
