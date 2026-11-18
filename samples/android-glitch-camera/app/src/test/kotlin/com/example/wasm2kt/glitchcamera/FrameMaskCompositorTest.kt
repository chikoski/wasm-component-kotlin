package com.example.wasm2kt.glitchcamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameMaskCompositorTest {
    @Test
    fun `effectTargetPass metadata matches expected defaults and flags`() {
        assertEquals(EffectTargetPass.ALL, EffectTargetPass.DEFAULT)
        assertEquals(listOf("全体", "前景", "背景", "なし"), EffectTargetPass.entries.map { it.label })

        assertFalse(EffectTargetPass.ALL.requiresSegmentation)
        assertTrue(EffectTargetPass.FOREGROUND.requiresSegmentation)
        assertTrue(EffectTargetPass.BACKGROUND.requiresSegmentation)
        assertFalse(EffectTargetPass.NONE.requiresSegmentation)

        assertTrue(EffectTargetPass.ALL.requiresGlitchEngine)
        assertTrue(EffectTargetPass.FOREGROUND.requiresGlitchEngine)
        assertTrue(EffectTargetPass.BACKGROUND.requiresGlitchEngine)
        assertFalse(EffectTargetPass.NONE.requiresGlitchEngine)
    }

    @Test
    fun `quantizeConfidenceFloats handles boundary values, infinities, NaN, buffer offset, and reuse`() {
        val rawFloats = floatArrayOf(
            999f,
            0.0f,
            0.5f,
            1.0f,
            -0.25f,
            1.5f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )
        val buffer = ByteBuffer.allocateDirect(rawFloats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(rawFloats)
                position(1) // Skip sentinel 999f
            }

        val reusable = ByteArray(8)
        val mask = FrameMaskCompositor.quantizeConfidenceFloats(
            floats = buffer,
            maskWidth = 4,
            maskHeight = 2,
            reuseBuffer = reusable,
        )

        assertEquals(1, buffer.position())
        assertSame(reusable, mask.weights)
        assertEquals(0, mask.weights[0].toInt() and 0xFF)
        assertEquals(128, mask.weights[1].toInt() and 0xFF)
        assertEquals(255, mask.weights[2].toInt() and 0xFF)
        assertEquals(0, mask.weights[3].toInt() and 0xFF)
        assertEquals(255, mask.weights[4].toInt() and 0xFF)
        assertEquals(0, mask.weights[5].toInt() and 0xFF)
        assertEquals(255, mask.weights[6].toInt() and 0xFF)
        assertEquals(0, mask.weights[7].toInt() and 0xFF)

        val wrongSizeReuse = ByteArray(2)
        buffer.position(1)
        val newMask = FrameMaskCompositor.quantizeConfidenceFloats(buffer, 4, 2, wrongSizeReuse)
        assertEquals(8, newMask.weights.size)
        assertArrayEquals(mask.weights, newMask.weights)
    }

    @Test
    fun `copyOpaqueRgba preserves RGB, forces opaque alpha, and reuses matching buffer`() {
        val source = byteArrayOf(10, 20, 30, 0x00, 40, 50, 60, 0x7F)
        val fresh = FrameMaskCompositor.copyOpaqueRgba(source)
        assertNotSame(source, fresh)
        assertArrayEquals(byteArrayOf(10, 20, 30, 0xFF.toByte(), 40, 50, 60, 0xFF.toByte()), fresh)

        val dest = ByteArray(source.size)
        val reused = FrameMaskCompositor.copyOpaqueRgba(source, dest)
        assertSame(dest, reused)
        assertArrayEquals(fresh, reused)

        val mismatched = ByteArray(4)
        val allocated = FrameMaskCompositor.copyOpaqueRgba(source, mismatched)
        assertNotSame(mismatched, allocated)
        assertArrayEquals(fresh, allocated)
    }

    @Test
    fun `blendInPlace blends all four passes including soft edges and forces opaque alpha`() {
        val width = 5
        val height = 1
        // Original: (10, 20, 30, 0x40) for every pixel
        val original = ByteArray(width * height * 4) { idx ->
            when (idx % 4) {
                0 -> 10.toByte()
                1 -> 20.toByte()
                2 -> 30.toByte()
                else -> 0x40.toByte()
            }
        }
        // Glitched: (210, 220, 230, 0x10) for every pixel
        fun newGlitched(): ByteArray = ByteArray(width * height * 4) { idx ->
            when (idx % 4) {
                0 -> 210.toByte()
                1 -> 220.toByte()
                2 -> 230.toByte()
                else -> 0x10.toByte()
            }
        }

        val weights = byteArrayOf(0.toByte(), 64.toByte(), 128.toByte(), 192.toByte(), 255.toByte())
        val mask = ForegroundMask(width, height, weights)

        // ALL: keeps glitched RGB, forces opaque alpha
        val allOut = newGlitched()
        FrameMaskCompositor.blendInPlace(original, allOut, width, height, mask, EffectTargetPass.ALL)
        for (p in 0 until width) {
            val i = p * 4
            assertEquals(210, allOut[i].toInt() and 0xFF)
            assertEquals(220, allOut[i + 1].toInt() and 0xFF)
            assertEquals(230, allOut[i + 2].toInt() and 0xFF)
            assertEquals(0xFF, allOut[i + 3].toInt() and 0xFF)
        }

        // NONE: restores original RGB, forces opaque alpha
        val noneOut = newGlitched()
        FrameMaskCompositor.blendInPlace(original, noneOut, width, height, mask, EffectTargetPass.NONE)
        for (p in 0 until width) {
            val i = p * 4
            assertEquals(10, noneOut[i].toInt() and 0xFF)
            assertEquals(20, noneOut[i + 1].toInt() and 0xFF)
            assertEquals(30, noneOut[i + 2].toInt() and 0xFF)
            assertEquals(0xFF, noneOut[i + 3].toInt() and 0xFF)
        }

        // FOREGROUND: m = fgWeight
        val fgOut = newGlitched()
        FrameMaskCompositor.blendInPlace(original, fgOut, width, height, mask, EffectTargetPass.FOREGROUND)
        for (p in 0 until width) {
            val m = weights[p].toInt() and 0xFF
            val inv = 255 - m
            val i = p * 4
            assertEquals((10 * inv + 210 * m + 127) / 255, fgOut[i].toInt() and 0xFF)
            assertEquals((20 * inv + 220 * m + 127) / 255, fgOut[i + 1].toInt() and 0xFF)
            assertEquals((30 * inv + 230 * m + 127) / 255, fgOut[i + 2].toInt() and 0xFF)
            assertEquals(0xFF, fgOut[i + 3].toInt() and 0xFF)
        }

        // BACKGROUND: m = 255 - fgWeight
        val bgOut = newGlitched()
        FrameMaskCompositor.blendInPlace(original, bgOut, width, height, mask, EffectTargetPass.BACKGROUND)
        for (p in 0 until width) {
            val m = 255 - (weights[p].toInt() and 0xFF)
            val inv = 255 - m
            val i = p * 4
            assertEquals((10 * inv + 210 * m + 127) / 255, bgOut[i].toInt() and 0xFF)
            assertEquals((20 * inv + 220 * m + 127) / 255, bgOut[i + 1].toInt() and 0xFF)
            assertEquals((30 * inv + 230 * m + 127) / 255, bgOut[i + 2].toInt() and 0xFF)
            assertEquals(0xFF, bgOut[i + 3].toInt() and 0xFF)
        }
    }

    @Test
    fun `blendInPlace scales mask for non-integer upscaling and downscaling`() {
        // Upscale non-integer ratio: 3x3 mask -> 5x4 frame
        val upWidth = 5
        val upHeight = 4
        val upOrig = ByteArray(upWidth * upHeight * 4) { idx -> if (idx % 4 == 3) 0xFF.toByte() else 10.toByte() }
        val upGlitch = ByteArray(upWidth * upHeight * 4) { idx -> if (idx % 4 == 3) 0x80.toByte() else 200.toByte() }
        val mask3x3 = ForegroundMask(
            width = 3,
            height = 3,
            weights = byteArrayOf(
                0, 255.toByte(), 0,
                255.toByte(), 0, 255.toByte(),
                0, 0, 255.toByte(),
            ),
        )
        FrameMaskCompositor.blendInPlace(upOrig, upGlitch, upWidth, upHeight, mask3x3, EffectTargetPass.FOREGROUND)
        for (y in 0 until upHeight) {
            val my = (y * 3) / upHeight
            for (x in 0 until upWidth) {
                val mx = (x * 3) / upWidth
                val offset = (y * upWidth + x) * 4
                val isFg = (mask3x3.weights[my * 3 + mx].toInt() and 0xFF) == 255
                val expectedRgb = if (isFg) 200 else 10
                assertEquals(expectedRgb, upGlitch[offset].toInt() and 0xFF)
                assertEquals(expectedRgb, upGlitch[offset + 1].toInt() and 0xFF)
                assertEquals(expectedRgb, upGlitch[offset + 2].toInt() and 0xFF)
                assertEquals(0xFF, upGlitch[offset + 3].toInt() and 0xFF)
            }
        }

        // Downscale: 4x4 mask -> 2x2 frame
        val downWidth = 2
        val downHeight = 2
        val downOrig = ByteArray(downWidth * downHeight * 4) { idx -> if (idx % 4 == 3) 0xFF.toByte() else 20.toByte() }
        val downGlitch = ByteArray(downWidth * downHeight * 4) { idx -> if (idx % 4 == 3) 0x00.toByte() else 220.toByte() }
        val mask4x4 = ForegroundMask(
            width = 4,
            height = 4,
            weights = ByteArray(16) { idx ->
                val mx = idx % 4
                val my = idx / 4
                if (mx == 0 && my == 0) 255.toByte() else 0
            },
        )
        FrameMaskCompositor.blendInPlace(downOrig, downGlitch, downWidth, downHeight, mask4x4, EffectTargetPass.FOREGROUND)
        assertEquals(220, downGlitch[0].toInt() and 0xFF) // (0,0) -> mx=0,my=0 -> FG
        assertEquals(20, downGlitch[4].toInt() and 0xFF)  // (1,0) -> mx=2,my=0 -> BG
        assertEquals(20, downGlitch[8].toInt() and 0xFF)  // (0,1) -> mx=0,my=2 -> BG
        assertEquals(20, downGlitch[12].toInt() and 0xFF) // (1,1) -> mx=2,my=2 -> BG
        for (i in 3 until downGlitch.size step 4) {
            assertEquals(0xFF, downGlitch[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `compositor validates invalid dimensions, buffer sizes, and integer overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundMask(0, 2, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundMask(2, 2, ByteArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundMask(50_000, 50_000, ByteArray(1))
        }

        val smallFloats = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.quantizeConfidenceFloats(smallFloats, 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.quantizeConfidenceFloats(smallFloats, 2, 2) // needs 4 floats, has 2
        }

        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.copyOpaqueRgba(ByteArray(3))
        }

        val validMask = ForegroundMask(2, 2, ByteArray(4))
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.blendInPlace(ByteArray(16), ByteArray(16), 0, 2, validMask, EffectTargetPass.FOREGROUND)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.blendInPlace(ByteArray(12), ByteArray(16), 2, 2, validMask, EffectTargetPass.FOREGROUND)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.blendInPlace(ByteArray(16), ByteArray(12), 2, 2, validMask, EffectTargetPass.FOREGROUND)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameMaskCompositor.blendInPlace(ByteArray(16), ByteArray(16), 30_000, 30_000, validMask, EffectTargetPass.ALL)
        }
    }

    @Test
    fun `selectForegroundMaskIndex resolves single-mask and multi-mask label layouts`() {
        assertEquals(0, MediaPipeFrameSegmenter.selectForegroundMaskIndex(listOf("selfie"), 1))
        assertEquals(1, MediaPipeFrameSegmenter.selectForegroundMaskIndex(listOf("background", "person"), 2))
        assertEquals(1, MediaPipeFrameSegmenter.selectForegroundMaskIndex(listOf("background", "selfie"), 2))
        assertEquals(0, MediaPipeFrameSegmenter.selectForegroundMaskIndex(listOf("foreground", "background"), 2))
        assertEquals(1, MediaPipeFrameSegmenter.selectForegroundMaskIndex(emptyList(), 2))
    }
}
