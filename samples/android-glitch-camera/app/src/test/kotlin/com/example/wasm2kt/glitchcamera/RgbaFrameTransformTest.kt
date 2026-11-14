package com.example.wasm2kt.glitchcamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RgbaFrameTransformTest {
    private val rotations = listOf(0, 90, 180, 270)

    @Test
    fun matchesReferenceWithoutScaling() {
        for (rotation in rotations) {
            for (mirror in listOf(false, true)) {
                for (padding in listOf(0, 12)) {
                    val src = SourceFrame(width = 7, height = 5, rowPadding = padding)
                    val actual = convert(src, rotation, mirror, maxLong = 1000, maxShort = 1000)
                    val expected = reference(src, rotation, mirror)
                    val label = "rotation=$rotation mirror=$mirror padding=$padding"
                    assertEquals(label, expected.width, actual.width)
                    assertEquals(label, expected.height, actual.height)
                    assertArrayEquals(label, expected.rgbaBytes, actual.rgbaBytes)
                }
            }
        }
    }

    @Test
    fun matchesReferenceWithBytePixelStride() {
        // A pixel stride of 6 forces byte addressing.
        for (rotation in rotations) {
            val src = SourceFrame(width = 4, height = 3, rowPadding = 2, pixelStride = 6)
            val actual = convert(src, rotation, mirror = true, maxLong = 100, maxShort = 100)
            val expected = reference(src, rotation, mirror = true)
            assertArrayEquals("rotation=$rotation", expected.rgbaBytes, actual.rgbaBytes)
        }
    }

    @Test
    fun downscalesToFitBoundsKeepingAspect() {
        val src = SourceFrame(width = 1920, height = 1080, rowPadding = 64)
        val landscape = convert(src, 0, false, maxLong = 640, maxShort = 360)
        assertEquals(640, landscape.width)
        assertEquals(360, landscape.height)
        val portrait = convert(src, 90, false, maxLong = 640, maxShort = 360)
        assertEquals(360, portrait.width)
        assertEquals(640, portrait.height)
        // 4:3 limited by the short side.
        val fourThree = convert(SourceFrame(width = 1280, height = 960), 90, false, maxLong = 1280, maxShort = 720)
        assertEquals(720, fourThree.width)
        assertEquals(960, fourThree.height)
    }

    @Test
    fun neverUpscales() {
        val src = SourceFrame(width = 320, height = 240)
        val frame = convert(src, 270, false, maxLong = 1280, maxShort = 720)
        assertEquals(240, frame.width)
        assertEquals(320, frame.height)
    }

    @Test
    fun downscaleSamplesPixelsOfTheRotatedFrame() {
        // A 2x downscale samples every other pixel of the rotated and mirrored frame.
        for (rotation in rotations) {
            for (mirror in listOf(false, true)) {
                val src = SourceFrame(width = 8, height = 6, rowPadding = 4)
                val full = reference(src, rotation, mirror)
                val half = convert(src, rotation, mirror, maxLong = 4, maxShort = 4)
                assertEquals(full.width / 2, half.width)
                assertEquals(full.height / 2, half.height)
                for (y in 0 until half.height) {
                    for (x in 0 until half.width) {
                        val fullOffset = ((2 * y + 1) * full.width + (2 * x + 1)) * 4
                        val halfOffset = (y * half.width + x) * 4
                        for (c in 0 until 4) {
                            assertEquals(
                                "rotation=$rotation mirror=$mirror ($x, $y)",
                                full.rgbaBytes[fullOffset + c],
                                half.rgbaBytes[halfOffset + c],
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun reusesTheOutputBuffer() {
        val scratch = RgbaBufferHolder()
        val src = SourceFrame(width = 16, height = 9)
        val first = convertInPlace(src, 90, true, scratch)
        val second = convertInPlace(src, 90, true, scratch)
        assertSame(first.rgbaBytes, second.rgbaBytes)
    }

    @Test
    fun handlesHeapBufferAndPreservesCallerPositionAndOrder() {
        val src = SourceFrame(width = 6, height = 4, rowPadding = 3, pixelStride = 5)
        val raw = ByteArray(src.buffer.remaining())
        src.buffer.duplicate().get(raw)
        val heap = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        heap.position(11)

        val actual = RgbaFrameTransform.convert(
            source = heap,
            srcWidth = src.width,
            srcHeight = src.height,
            rowStride = src.rowStride,
            pixelStride = src.pixelStride,
            rotationDegrees = 90,
            mirrorHorizontally = false,
            maxLongSide = 100,
            maxShortSide = 100,
            scratch = RgbaBufferHolder(),
        )
        val expected = reference(src, rotation = 90, mirror = false)
        assertEquals(11, heap.position())
        assertEquals(ByteOrder.BIG_ENDIAN, heap.order())
        assertArrayEquals(expected.rgbaBytes, actual.rgbaBytes)
    }

    private fun convertInPlace(src: SourceFrame, rotation: Int, mirror: Boolean, scratch: RgbaBufferHolder) =
        RgbaFrameTransform.convert(
            src.buffer, src.width, src.height, src.rowStride, src.pixelStride, rotation, mirror, 100, 100, scratch,
        )

    /** A camera plane whose pixel (x, y) is the bytes `x, y, 0x5A, 0xFF`. */
    private class SourceFrame(val width: Int, val height: Int, rowPadding: Int = 0, val pixelStride: Int = 4) {
        val rowStride = width * pixelStride + rowPadding
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(rowStride * (height - 1) + width * pixelStride).also { buf ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val at = y * rowStride + x * pixelStride
                    buf.put(at, x.toByte())
                    buf.put(at + 1, y.toByte())
                    buf.put(at + 2, 0x5A)
                    buf.put(at + 3, 0xFF.toByte())
                }
            }
        }
    }

    private fun convert(
        src: SourceFrame,
        rotation: Int,
        mirror: Boolean,
        maxLong: Int,
        maxShort: Int,
        scratch: RgbaBufferHolder = RgbaBufferHolder(),
    ): ExtractedFrame = RgbaFrameTransform.convert(
        source = src.buffer,
        srcWidth = src.width,
        srcHeight = src.height,
        rowStride = src.rowStride,
        pixelStride = src.pixelStride,
        rotationDegrees = rotation,
        mirrorHorizontally = mirror,
        maxLongSide = maxLong,
        maxShortSide = maxShort,
        scratch = scratch,
    ).let { ExtractedFrame(it.width, it.height, it.rgbaBytes.copyOf()) }

    /** The previous per-pixel implementation: maps each source pixel forward. */
    private fun reference(src: SourceFrame, rotation: Int, mirror: Boolean): ExtractedFrame {
        val swap = rotation == 90 || rotation == 270
        val outWidth = if (swap) src.height else src.width
        val outHeight = if (swap) src.width else src.height
        val out = ByteArray(outWidth * outHeight * 4)
        for (sy in 0 until src.height) {
            for (sx in 0 until src.width) {
                val (rotX, rotY) = when (rotation) {
                    90 -> (src.height - 1 - sy) to sx
                    180 -> (src.width - 1 - sx) to (src.height - 1 - sy)
                    270 -> sy to (src.width - 1 - sx)
                    else -> sx to sy
                }
                val dstX = if (mirror) outWidth - 1 - rotX else rotX
                val dst = (rotY * outWidth + dstX) * 4
                val at = sy * src.rowStride + sx * src.pixelStride
                for (c in 0 until 4) out[dst + c] = src.buffer.get(at + c)
            }
        }
        return ExtractedFrame(outWidth, outHeight, out)
    }
}
