package com.example.wasm2kt.glitchcamera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts an RGBA_8888 camera plane into tightly packed upright RGBA bytes in one pass:
 * rotation (`0`, `90`, `180`, `270`), optional horizontal mirroring, and a nearest-neighbour
 * downscale so the result fits within `maxLongSide` x `maxShortSide`. Frames are never upscaled.
 *
 * Kept free of Android types so it can be tested on the host JVM.
 *
 * The source position of every output pixel splits into a part that depends only on the output
 * column and a part that depends only on the output row, for every rotation. Both are
 * precomputed into tables, so the inner loop is one table lookup and one 4-byte copy per pixel
 * and allocates nothing.
 */
object RgbaFrameTransform {
    fun convert(
        source: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        maxLongSide: Int,
        maxShortSide: Int,
        scratch: RgbaBufferHolder,
    ): ExtractedFrame {
        require(srcWidth > 0 && srcHeight > 0) { "Empty frame ${srcWidth}x$srcHeight" }
        require(pixelStride >= 4) { "RGBA_8888 needs a pixel stride of at least 4, got $pixelStride" }
        require(maxLongSide > 0 && maxShortSide > 0) { "Invalid bounds ${maxLongSide}x$maxShortSide" }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "Unsupported rotation $rotationDegrees" }

        val swap = rotation == 90 || rotation == 270
        val rotWidth = if (swap) srcHeight else srcWidth
        val rotHeight = if (swap) srcWidth else srcHeight

        // Scale by the tighter of the two bounds, as the exact fraction num / den.
        val longSide = maxOf(rotWidth, rotHeight)
        val shortSide = minOf(rotWidth, rotHeight)
        val limitedByLong = maxLongSide.toLong() * shortSide <= maxShortSide.toLong() * longSide
        val num = if (limitedByLong) maxLongSide else maxShortSide
        val den = if (limitedByLong) longSide else shortSide
        val outWidth = if (num >= den) rotWidth else scaledLength(rotWidth, num, den)
        val outHeight = if (num >= den) rotHeight else scaledLength(rotHeight, num, den)

        val out = scratch.acquire(outWidth * outHeight * 4)
        val buffer = source.duplicate()
        buffer.position(0)

        if (rotation == 0 && !mirrorHorizontally && outWidth == srcWidth && outHeight == srcHeight &&
            pixelStride == 4 && rowStride == srcWidth * 4
        ) {
            buffer.get(out, 0, out.size)
            return ExtractedFrame(outWidth, outHeight, out)
        }

        // Rows and pixels are addressed in ints when the strides allow it, otherwise in bytes.
        val intAddressing = rowStride % 4 == 0 && pixelStride % 4 == 0
        val rowStep = if (intAddressing) rowStride / 4 else rowStride
        val pixelStep = if (intAddressing) pixelStride / 4 else pixelStride

        val columnTerms = scratch.acquireColumnTable(outWidth)
        for (ox in 0 until outWidth) {
            // Mirror in full-resolution coordinates: the result is the mirrored frame, downscaled.
            val sampled = sample(ox, outWidth, rotWidth)
            val rx = if (mirrorHorizontally) rotWidth - 1 - sampled else sampled
            columnTerms[ox] = when (rotation) {
                0 -> rx * pixelStep // sx = rx
                90 -> (srcHeight - 1 - rx) * rowStep // sy = srcHeight - 1 - rx
                180 -> (srcWidth - 1 - rx) * pixelStep // sx = srcWidth - 1 - rx
                else -> rx * rowStep // 270: sy = rx
            }
        }
        val rowTerms = scratch.acquireRowTable(outHeight)
        for (oy in 0 until outHeight) {
            val ry = sample(oy, outHeight, rotHeight)
            rowTerms[oy] = when (rotation) {
                0 -> ry * rowStep // sy = ry
                90 -> ry * pixelStep // sx = ry
                180 -> (srcHeight - 1 - ry) * rowStep // sy = srcHeight - 1 - ry
                else -> (srcWidth - 1 - ry) * pixelStep // 270: sx = srcWidth - 1 - ry
            }
        }

        if (intAddressing) {
            // Native order on both sides keeps the R, G, B, A bytes in place.
            val srcInts = buffer.order(ByteOrder.nativeOrder()).asIntBuffer()
            val srcCount = srcInts.remaining()
            val src = scratch.acquireSourceInts(srcCount)
            srcInts.get(src, 0, srcCount)
            val dst = scratch.acquireOutputInts(outWidth * outHeight)
            var d = 0
            for (oy in 0 until outHeight) {
                val base = rowTerms[oy]
                for (ox in 0 until outWidth) {
                    dst[d++] = src[base + columnTerms[ox]]
                }
            }
            ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).asIntBuffer().put(dst, 0, outWidth * outHeight)
        } else {
            val srcCount = buffer.remaining()
            val src = scratch.acquireSourceBytes(srcCount)
            buffer.get(src, 0, srcCount)
            var d = 0
            for (oy in 0 until outHeight) {
                val base = rowTerms[oy]
                for (ox in 0 until outWidth) {
                    val s = base + columnTerms[ox]
                    out[d] = src[s]
                    out[d + 1] = src[s + 1]
                    out[d + 2] = src[s + 2]
                    out[d + 3] = src[s + 3]
                    d += 4
                }
            }
        }
        return ExtractedFrame(outWidth, outHeight, out)
    }

    /** `length * num / den`, rounded to nearest, at least 1. */
    private fun scaledLength(length: Int, num: Int, den: Int): Int =
        maxOf(1, ((length.toLong() * num + den / 2) / den).toInt())

    /** The source index (`0 until full`) sampled by output index [i] of [out] (pixel centres). */
    private fun sample(i: Int, out: Int, full: Int): Int =
        ((2L * i + 1) * full / (2L * out)).toInt()
}

/** Buffers reused across frames by [RgbaFrameTransform]. Not thread-safe. */
class RgbaBufferHolder {
    private var frameBuffer = ByteArray(0)
    private var sourceInts = IntArray(0)
    private var sourceBytes = ByteArray(0)
    private var outputInts = IntArray(0)
    private var columnTable = IntArray(0)
    private var rowTable = IntArray(0)

    /** The output buffer, exactly [size] bytes long (it is handed to the engine as is). */
    fun acquire(size: Int): ByteArray {
        if (frameBuffer.size != size) frameBuffer = ByteArray(size)
        return frameBuffer
    }

    internal fun acquireSourceInts(size: Int): IntArray {
        if (sourceInts.size < size) sourceInts = IntArray(size)
        return sourceInts
    }

    internal fun acquireSourceBytes(size: Int): ByteArray {
        if (sourceBytes.size < size) sourceBytes = ByteArray(size)
        return sourceBytes
    }

    internal fun acquireOutputInts(size: Int): IntArray {
        if (outputInts.size < size) outputInts = IntArray(size)
        return outputInts
    }

    internal fun acquireColumnTable(size: Int): IntArray {
        if (columnTable.size < size) columnTable = IntArray(size)
        return columnTable
    }

    internal fun acquireRowTable(size: Int): IntArray {
        if (rowTable.size < size) rowTable = IntArray(size)
        return rowTable
    }
}

class ExtractedFrame(
    val width: Int,
    val height: Int,
    val rgbaBytes: ByteArray,
)
