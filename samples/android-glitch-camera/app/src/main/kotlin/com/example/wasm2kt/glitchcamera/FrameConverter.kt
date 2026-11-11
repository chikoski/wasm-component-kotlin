package com.example.wasm2kt.glitchcamera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object FrameConverter {
    /**
     * Extracts tightly packed upright RGBA_8888 bytes (`outWidth * outHeight * 4`) from a
     * CameraX [ImageProxy] produced with `OUTPUT_IMAGE_FORMAT_RGBA_8888`, applying the frame's
     * rotation (`0`, `90`, `180`, `270`) and optional horizontal mirroring for the front camera.
     */
    fun extractRgba(
        image: ImageProxy,
        mirrorHorizontally: Boolean,
        scratchHolder: RgbaBufferHolder,
    ): ExtractedFrame {
        val srcWidth = image.width
        val srcHeight = image.height
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val outWidth = if (rotation == 90 || rotation == 270) srcHeight else srcWidth
        val outHeight = if (rotation == 90 || rotation == 270) srcWidth else srcHeight

        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val outBytes = scratchHolder.acquire(outWidth * outHeight * 4)
        buffer.rewind()

        if (rotation == 0 && !mirrorHorizontally && pixelStride == 4 && rowStride == srcWidth * 4) {
            buffer.get(outBytes, 0, outBytes.size)
            return ExtractedFrame(outWidth, outHeight, outBytes)
        }

        val srcRowBytes = scratchHolder.acquireRow(rowStride)
        for (sy in 0 until srcHeight) {
            val rowStart = sy * rowStride
            val bytesToRead = minOf(rowStride, buffer.capacity() - rowStart)
            if (bytesToRead <= 0) break
            buffer.position(rowStart)
            buffer.get(srcRowBytes, 0, bytesToRead)

            for (sx in 0 until srcWidth) {
                val srcOffset = sx * pixelStride
                val (rotX, rotY) = when (rotation) {
                    90 -> (srcHeight - 1 - sy) to sx
                    180 -> (srcWidth - 1 - sx) to (srcHeight - 1 - sy)
                    270 -> sy to (srcWidth - 1 - sx)
                    else -> sx to sy
                }
                val dstX = if (mirrorHorizontally) (outWidth - 1 - rotX) else rotX
                val dstOffset = (rotY * outWidth + dstX) * 4
                outBytes[dstOffset] = srcRowBytes[srcOffset]
                outBytes[dstOffset + 1] = srcRowBytes[srcOffset + 1]
                outBytes[dstOffset + 2] = srcRowBytes[srcOffset + 2]
                outBytes[dstOffset + 3] = srcRowBytes[srcOffset + 3]
            }
        }
        return ExtractedFrame(outWidth, outHeight, outBytes)
    }
}

class RgbaBufferHolder {
    private var frameBuffer: ByteArray = ByteArray(0)
    private var rowBuffer: ByteArray = ByteArray(0)

    fun acquire(size: Int): ByteArray {
        if (frameBuffer.size != size) {
            frameBuffer = ByteArray(size)
        }
        return frameBuffer
    }

    fun acquireRow(size: Int): ByteArray {
        if (rowBuffer.size < size) {
            rowBuffer = ByteArray(size)
        }
        return rowBuffer
    }
}

class ExtractedFrame(
    val width: Int,
    val height: Int,
    val rgbaBytes: ByteArray,
)
