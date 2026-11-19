package com.example.wasm2kt.glitchcamera

import com.google.mediapipe.proto.CalculatorProto
import com.google.mediapipe.tasks.core.CpuSelfieSegmenterGraph
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

    @Test
    fun `quantizeConfidenceFloatArray handles boundary values, buffer reuse, and subject threshold`() {
        val raw = floatArrayOf(0.0f, 0.5f, 1.0f, -0.25f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val reusable = ByteArray(8)
        val mask = FrameMaskCompositor.quantizeConfidenceFloatArray(
            floats = raw,
            maskWidth = 4,
            maskHeight = 2,
            reuseBuffer = reusable,
        )
        assertSame(reusable, mask.weights)
        assertEquals(0, mask.weights[0].toInt() and 0xFF)
        assertEquals(128, mask.weights[1].toInt() and 0xFF)
        assertEquals(255, mask.weights[2].toInt() and 0xFF)
        assertEquals(0, mask.weights[3].toInt() and 0xFF)
        assertEquals(255, mask.weights[4].toInt() and 0xFF)
        assertEquals(0, mask.weights[5].toInt() and 0xFF)
        assertEquals(255, mask.weights[6].toInt() and 0xFF)
        assertEquals(0, mask.weights[7].toInt() and 0xFF)
        assertTrue(mask.hasForegroundSubject)

        // 100 pixels -> 1.5% threshold is max(1, 100*15/1000) = 1 pixel.
        // 200 pixels -> 200*15/1000 = 3 pixels.
        val sparse = FloatArray(200) { idx ->
            when {
                idx < 2 -> 0.8f // 2 pixels >= 0.5f (below 3-pixel threshold)
                idx < 10 -> 0.49f // quantized to 125 (< 128)
                else -> 0.0f
            }
        }
        val sparseMask = FrameMaskCompositor.quantizeConfidenceFloatArray(sparse, 20, 10)
        assertFalse(sparseMask.hasForegroundSubject)

        val enough = sparse.copyOf().apply { this[2] = 0.5f }
        val subjectMask = FrameMaskCompositor.quantizeConfidenceFloatArray(enough, 20, 10)
        assertTrue(subjectMask.hasForegroundSubject)

        val logits = floatArrayOf(-10f, 0f, 10f, Float.NaN)
        val sigmoidMask = FrameMaskCompositor.quantizeConfidenceFloatArray(
            floats = logits,
            maskWidth = 2,
            maskHeight = 2,
            applySigmoid = true,
        )
        assertEquals(0, sigmoidMask.weights[0].toInt() and 0xFF)
        assertEquals(128, sigmoidMask.weights[1].toInt() and 0xFF)
        assertEquals(255, sigmoidMask.weights[2].toInt() and 0xFF)
        assertEquals(0, sigmoidMask.weights[3].toInt() and 0xFF)
    }

    @Test
    fun `rewriteExpandedConfigForCpu replaces TensorsToSegmentationCalculator and drops transitive dependents`() {
        fun nodeBuilder() = CalculatorProto.CalculatorGraphConfig.Node.newBuilder()
        val expanded = CalculatorProto.CalculatorGraphConfig.newBuilder()
            .addInputStream("IMAGE:image_in")
            .addInputStream("NORM_RECT:norm_rect_in")
            .addInputStream("OUTPUT_SIZE:output_size_in")
            .addOutputStream("CONFIDENCE_MASKS:confidence_masks")
            .addOutputStream("IMAGE:image_out")
            .addNode(
                nodeBuilder()
                    .setCalculator("ImagePropertiesCalculator")
                    .addInputStream("IMAGE:image_in")
                    .addOutputStream("SIZE:image_size"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("ImageToTensorCalculator")
                    .addInputStream("IMAGE:image_in")
                    .addInputStream("NORM_RECT:norm_rect_in")
                    .addOutputStream("TENSORS:input_tensors"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("InferenceCalculatorCpu")
                    .addInputStream("TENSORS:input_tensors")
                    .addOutputStream("TENSORS:output_tensors"),
            )
            .addNode(
                nodeBuilder()
                    .setName("mediapipe_tasks_vision_image_segmenter_imagesegmentergraph__mediapipe.tasks.TensorsToSegmentationCalculator")
                    .setCalculator("mediapipe.tasks.TensorsToSegmentationCalculator")
                    .addInputStream("TENSORS:output_tensors")
                    .addInputStream("OUTPUT_SIZE:output_size_in")
                    .addOutputStream("CONFIDENCE_MASK:raw_mask"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("PassThroughCalculator")
                    .addInputStream("image_in")
                    .addOutputStream("image_out"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("MergeImagesToVectorCalculator")
                    .addInputStream("raw_mask")
                    .addOutputStream("CONFIDENCE_MASKS:merged_masks"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("PassThroughCalculator")
                    .addInputStream("CONFIDENCE_MASKS:merged_masks")
                    .addOutputStream("CONFIDENCE_MASKS:confidence_masks"),
            )
            .addNode(
                nodeBuilder()
                    .setCalculator("PassThroughCalculator")
                    .addInputStream("SIZE:image_size")
                    .addOutputStream("SIZE:derived_size"),
            )
            .build()

        val spec = CpuSelfieSegmenterGraph.rewriteExpandedConfigForCpuSpec(expanded)
        val rewritten = spec.config

        assertEquals(
            listOf("ImageToTensorCalculator", "InferenceCalculatorCpu", "TensorsToFloatsCalculator"),
            rewritten.nodeList.map { it.calculator },
        )
        assertEquals(listOf("IMAGE:image_in", "NORM_RECT:norm_rect_in"), rewritten.inputStreamList)
        assertEquals(listOf("FLOATS:confidence_floats"), rewritten.outputStreamList)
        assertFalse(spec.requiresSigmoid)

        val floatsNode = rewritten.nodeList.last()
        assertEquals(listOf("TENSORS:output_tensors"), floatsNode.inputStreamList)
        assertEquals(listOf("FLOATS:confidence_floats"), floatsNode.outputStreamList)
    }

    @Test
    fun `segmentationStatusTracker handles pass transitions, late frames, hysteresis, and expected message clear`() {
        val tracker = SegmentationStatusTracker(hysteresisFrames = 3)

        // Switching ALL -> FOREGROUND enters NO_SUBJECT and applies the very first sample immediately.
        var status = tracker.resolveForPassSelection(SegmentationStatus.INACTIVE, EffectTargetPass.FOREGROUND)
        assertEquals(SegmentationStatus.NO_SUBJECT, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.FOREGROUND, status, SegmentationStatus.DETECTED)
        assertEquals(SegmentationStatus.DETECTED, status)

        // Switching FOREGROUND -> BACKGROUND keeps current status without resetting to NO_SUBJECT.
        status = tracker.resolveForPassSelection(status, EffectTargetPass.BACKGROUND)
        assertEquals(SegmentationStatus.DETECTED, status)

        // Flipping DETECTED -> NO_SUBJECT requires 3 consecutive NO_SUBJECT frames (1 or 2 frames do not flip).
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.NO_SUBJECT)
        assertEquals(SegmentationStatus.DETECTED, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.NO_SUBJECT)
        assertEquals(SegmentationStatus.DETECTED, status)
        // A single DETECTED frame resets the streak cleanly.
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.DETECTED)
        assertEquals(SegmentationStatus.DETECTED, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.NO_SUBJECT)
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.NO_SUBJECT)
        assertEquals(SegmentationStatus.DETECTED, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.NO_SUBJECT)
        assertEquals(SegmentationStatus.NO_SUBJECT, status)

        // UNAVAILABLE takes effect immediately without hysteresis delay.
        status = tracker.resolveForFrameOutput(EffectTargetPass.BACKGROUND, status, SegmentationStatus.UNAVAILABLE)
        assertEquals(SegmentationStatus.UNAVAILABLE, status)

        // Switching back to ALL transitions to INACTIVE and ignores late segmented frame outputs.
        status = tracker.resolveForPassSelection(status, EffectTargetPass.ALL)
        assertEquals(SegmentationStatus.INACTIVE, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.ALL, status, SegmentationStatus.DETECTED)
        assertEquals(SegmentationStatus.INACTIVE, status)

        // Late frame reporting INACTIVE while FOREGROUND is now selected does not clobber NO_SUBJECT.
        status = tracker.resolveForPassSelection(status, EffectTargetPass.FOREGROUND)
        assertEquals(SegmentationStatus.NO_SUBJECT, status)
        status = tracker.resolveForFrameOutput(EffectTargetPass.FOREGROUND, status, SegmentationStatus.INACTIVE)
        assertEquals(SegmentationStatus.NO_SUBJECT, status)

        // Expected-message guard prevents stale auto-dismiss timers from wiping newer messages.
        assertFalse(SegmentationStatusTracker.shouldClearStatusMessage("Saved MP4", "Finalizing MP4..."))
        assertTrue(SegmentationStatusTracker.shouldClearStatusMessage("Saved MP4", "Saved MP4"))
        assertTrue(SegmentationStatusTracker.shouldClearStatusMessage("Anything", null))
    }
}
