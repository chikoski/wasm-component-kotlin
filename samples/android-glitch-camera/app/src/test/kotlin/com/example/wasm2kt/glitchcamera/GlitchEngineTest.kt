package com.example.wasm2kt.glitchcamera

import com.example.wasm2kt.glitchcamera.pngglitch.PngglitchComponent
import com.example.wasm2kt.glitchcamera.pngglitch.WitResult
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.Session as LiveSession
import com.example.wasm2kt.glitchcamera.pngglitch.openSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlitchEngineTest {
    @Test
    fun `availablePngFilters returns non-deprecated PNG filters and default recipes validate`() = runBlocking {
        GlitchEngine().use { engine ->
            val filters = engine.availablePngFilters()
            assertTrue("Expected at least 15 PNG filters", filters.size >= 15)
            assertTrue(filters.none { it.deprecated })

            for (preset in RecipePresets.curated) {
                val err = engine.validate(preset.yaml)
                assertNull("Curated preset ${preset.id} failed validation: $err", err)
            }

            for (filter in filters) {
                val defaultParams = RecipePresets.defaultParamsFor(filter)
                val yaml = RecipePresets.buildSingleFilterYaml(filter, defaultParams)
                val err = engine.validate(yaml)
                assertNull("Single-filter recipe for ${filter.name} failed validation: $err\n$yaml", err)
            }
        }
    }

    @Test
    fun `processFrame and captureSnapshotPng execute live session across multiple frames`() = runBlocking {
        val width = 32
        val height = 24
        val frame1 = ByteArray(width * height * 4) { idx ->
            when (idx % 4) {
                0 -> ((idx / 4) % 256).toByte()
                1 -> ((idx / 7) % 256).toByte()
                2 -> ((idx / 11) % 256).toByte()
                else -> 0xFF.toByte()
            }
        }
        val frame2 = ByteArray(width * height * 4) { idx ->
            when (idx % 4) {
                0 -> (255 - ((idx / 4) % 256)).toByte()
                1 -> ((idx / 5) % 256).toByte()
                2 -> ((idx / 3) % 256).toByte()
                else -> 0xFF.toByte()
            }
        }

        GlitchEngine().use { engine ->
            engine.updateConfig(
                recipeYaml = """
                    filters:
                      - type: Invert
                """.trimIndent(),
                seed = 123uL,
                seedMode = SeedMode.FIXED,
                filterOption = PngFilterOption.ADAPTIVE_FAST,
            )

            val out1 = engine.processFrame(width, height, frame1).getOrThrow()
            assertEquals(width * height * 4, out1.rgbaBytes.size)
            assertFalse("Expected glitched RGBA to differ from input", out1.rgbaBytes.contentEquals(frame1))

            val out2 = engine.processFrame(width, height, frame2).getOrThrow()
            assertEquals(width * height * 4, out2.rgbaBytes.size)
            assertFalse("Expected second frame output to differ from first frame", out2.rgbaBytes.contentEquals(out1.rgbaBytes))

            val pngBytes = engine.captureSnapshotPng().getOrThrow()
            assertTrue("Expected non-empty PNG snapshot", pngBytes.size > 64)
            // Verify PNG signature: 89 50 4E 47 0D 0A 1A 0A
            val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(pngBytes.sliceArray(0 until 8).contentEquals(expectedSig))

            val recipes = mapOf(
                "preset_0" to RecipePresets.curated.first().yaml,
                "invert" to "filters:\n  - type: Invert",
            )
            val thumbs = engine.generateThumbnails(width, height, frame1, recipes)
            assertEquals(2, thumbs.size)
            assertEquals(width * height * 4, thumbs.getValue("preset_0").size)
            assertEquals(width * height * 4, thumbs.getValue("invert").size)
            assertFalse(thumbs.getValue("invert").contentEquals(frame1))
        }
    }

    @Test
    fun `processFrame advances PerFrame seed across identical inputs`() = runBlocking {
        val width = 16
        val height = 16
        val frame = ByteArray(width * height * 4) { idx ->
            if (idx % 4 == 3) 0xFF.toByte() else (idx % 251).toByte()
        }
        GlitchEngine().use { engine ->
            engine.updateConfig(
                recipeYaml = """
                    filters:
                      - type: Replace
                        magnitude: 0.3
                """.trimIndent(),
                seed = 7uL,
                seedMode = SeedMode.PER_FRAME,
                filterOption = PngFilterOption.NONE,
            )
            val first = engine.processFrame(width, height, frame).getOrThrow()
            val second = engine.processFrame(width, height, frame).getOrThrow()
            assertEquals(width * height * 4, first.rgbaBytes.size)
            assertEquals(width * height * 4, second.rgbaBytes.size)
            assertFalse("Expected PerFrame seed progression on repeated identical frame", first.rgbaBytes.contentEquals(second.rgbaBytes))
        }
    }

    @Test
    fun `rgbEncodingPreservesOpaqueAlphaAndProducesRgbPngSnapshot`() = runBlocking {
        assertEquals(PngFilterOption.PAETH, PngFilterOption.DEFAULT)
        val width = 16
        val height = 16
        val semiTransparentFrame = createTestFrame(width, height, alpha = 0x40.toByte())
        val identityRgbYaml = """
            filters:
              - type: Regenerate
                strategy:
                  fixed: none
        """.trimIndent()
        val byteCorruptionYaml = """
            filters:
              - type: Replace
                magnitude: 0.25
              - type: Transpose
                magnitude: 0.25
              - type: HorizontalShift
                magnitude: 0.50
        """.trimIndent()

        GlitchEngine().use { engine ->
            engine.updateConfig(
                recipeYaml = identityRgbYaml,
                seed = 99uL,
                seedMode = SeedMode.FIXED,
                filterOption = PngFilterOption.PAETH,
            )
            val unpacked = engine.processFrame(width, height, semiTransparentFrame).getOrThrow()
            for (i in unpacked.rgbaBytes.indices step 4) {
                // RGB encoding discards input alpha (0x40) without premultiplying RGB and outputs 0xFF.
                assertEquals(semiTransparentFrame[i], unpacked.rgbaBytes[i])
                assertEquals(semiTransparentFrame[i + 1], unpacked.rgbaBytes[i + 1])
                assertEquals(semiTransparentFrame[i + 2], unpacked.rgbaBytes[i + 2])
                assertEquals(0xFF.toByte(), unpacked.rgbaBytes[i + 3])
            }

            engine.updateConfig(
                recipeYaml = byteCorruptionYaml,
                seed = 99uL,
                seedMode = SeedMode.FIXED,
                filterOption = PngFilterOption.PAETH,
            )
            val output = engine.processFrame(width, height, semiTransparentFrame).getOrThrow()
            assertFalse("Expected glitched output to differ from input", output.rgbaBytes.contentEquals(semiTransparentFrame))
            assertAllPixelsOpaque(output.rgbaBytes)

            val pngBytes = engine.captureSnapshotPng().getOrThrow()
            val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(pngBytes.sliceArray(0 until 8).contentEquals(expectedSig))
            // PNG IHDR offset 25 stores the color type byte (2 = TrueColor RGB, 6 = RGBA).
            assertEquals("Expected RGB PNG IHDR color type (2)", 2, pngBytes[25].toInt() and 0xFF)

            val thumbs = engine.generateThumbnails(
                width = width,
                height = height,
                baseRgba = semiTransparentFrame,
                recipes = mapOf("corrupt" to byteCorruptionYaml),
                seed = 99uL,
                filterOption = PngFilterOption.PAETH,
            )
            assertAllPixelsOpaque(thumbs.getValue("corrupt"))
        }
    }

    @Test
    fun `differentialFilterPresetsPropagateAcrossPixels`() = runBlocking {
        val width = 32
        val height = 32
        val frame = createTestFrame(width, height)
        val rawReplaceYaml = """
            filters:
              - type: Regenerate
                strategy:
                  fixed: none
              - type: Replace
                magnitude: 0.25
        """.trimIndent()
        val subReplaceYaml = """
            filters:
              - type: Regenerate
                strategy:
                  fixed: sub
              - type: Replace
                magnitude: 0.25
        """.trimIndent()

        GlitchEngine().use { engine ->
            engine.updateConfig(rawReplaceYaml, seed = 42uL, SeedMode.FIXED, PngFilterOption.NONE)
            val rawOut = engine.processFrame(width, height, frame).getOrThrow()

            engine.updateConfig(subReplaceYaml, seed = 42uL, SeedMode.FIXED, PngFilterOption.SUB)
            val subOut = engine.processFrame(width, height, frame).getOrThrow()

            val rawChanged = countChangedPixels(frame, rawOut.rgbaBytes)
            val subChanged = countChangedPixels(frame, subOut.rgbaBytes)
            assertTrue("Expected raw Replace to modify pixels", rawChanged > 0)
            assertTrue(
                "Expected Sub residual Replace ($subChanged changed pixels) to propagate further than raw Replace ($rawChanged changed pixels)",
                subChanged > rawChanged,
            )

            for (presetId in listOf("rgb_drift", "paeth_melt", "block_chaos", "re_encode_cascade")) {
                val preset = RecipePresets.curated.first { it.id == presetId }
                engine.updateConfig(preset.yaml, seed = 42uL, SeedMode.FIXED, PngFilterOption.DEFAULT)
                val firstOut = engine.processFrame(width, height, frame).getOrThrow()
                val secondOut = engine.processFrame(width, height, frame).getOrThrow()
                assertArrayEquals(
                    "Expected deterministic output for preset $presetId under SeedMode.FIXED",
                    firstOut.rgbaBytes,
                    secondOut.rgbaBytes,
                )
                assertAllPixelsOpaque(firstOut.rgbaBytes)
                assertTrue(
                    "Expected preset $presetId to modify multiple pixels",
                    countChangedPixels(frame, firstOut.rgbaBytes) > 1,
                )
            }
        }
    }

    @Test
    fun `pixelDomainPresetsAreConsistentUnderPaethFilterOption`() = runBlocking {
        val width = 16
        val height = 16
        val frame = createTestFrame(width, height)

        GlitchEngine().use { engine ->
            val filtersByName = engine.availablePngFilters().associateBy { it.name }
            for (name in RecipePresets.PIXEL_DOMAIN_FILTERS) {
                assertTrue("Expected pixel-domain filter '$name' to exist in availablePngFilters()", name in filtersByName)
            }

            val singlePixelDomainRecipes = RecipePresets.PIXEL_DOMAIN_FILTERS.map { name ->
                val filter = filtersByName.getValue(name)
                val yaml = RecipePresets.buildSingleFilterYaml(filter, RecipePresets.defaultParamsFor(filter))
                assertTrue(
                    "Expected Regenerate(fixed: none) prefix for pixel-domain filter '$name'\n$yaml",
                    yaml.contains("type: Regenerate") && yaml.contains("fixed: none"),
                )
                yaml
            }

            for (name in listOf("replace", "transpose", "horizontal-shift", "change-filter-type")) {
                val filter = filtersByName.getValue(name)
                val yaml = RecipePresets.buildSingleFilterYaml(filter, RecipePresets.defaultParamsFor(filter))
                assertFalse(
                    "Expected no Regenerate prefix for differential filter '$name'\n$yaml",
                    yaml.contains("type: Regenerate"),
                )
            }

            val recipes = listOf(
                RecipePresets.curated.first { it.id == "cyber_sort" }.yaml,
                RecipePresets.curated.first { it.id == "neon_hsl" }.yaml,
            ) + singlePixelDomainRecipes

            for (yaml in recipes) {
                engine.updateConfig(yaml, seed = 42uL, SeedMode.FIXED, PngFilterOption.NONE)
                val noneOut = engine.processFrame(width, height, frame).getOrThrow()

                engine.updateConfig(yaml, seed = 42uL, SeedMode.FIXED, PngFilterOption.PAETH)
                val paethOut = engine.processFrame(width, height, frame).getOrThrow()

                assertArrayEquals(noneOut.rgbaBytes, paethOut.rgbaBytes)
                assertAllPixelsOpaque(paethOut.rgbaBytes)
            }
        }
    }

    private fun createTestFrame(width: Int, height: Int, alpha: Byte = 0xFF.toByte()): ByteArray =
        ByteArray(width * height * 4) { idx ->
            val pixel = idx / 4
            val x = pixel % width
            val y = pixel / width
            when (idx % 4) {
                0 -> ((x * 7 + y * 3) % 256).toByte()
                1 -> ((x * 3 + y * 11) % 256).toByte()
                2 -> ((x * 5 + y * 13) % 256).toByte()
                else -> alpha
            }
        }

    private fun assertAllPixelsOpaque(rgba: ByteArray) {
        for (i in 3 until rgba.size step 4) {
            assertEquals("Pixel alpha byte at $i should remain opaque", 0xFF.toByte(), rgba[i])
        }
    }

    @Test
    fun `close rejects subsequent calls without recreating sessions and serializes with processFrame`() = runBlocking {
        val width = 16
        val height = 16
        val frame = createTestFrame(width, height)
        val engine = GlitchEngine()

        // Initialize a live session, then launch concurrent frame calls and close the engine.
        engine.processFrame(width, height, frame).getOrThrow()
        val concurrentFrames = List(4) {
            async(Dispatchers.Default) {
                engine.processFrame(width, height, frame)
            }
        }
        engine.close()
        val results = concurrentFrames.awaitAll()
        engine.awaitCloseForTest()
        for (res in results) {
            if (res.isFailure) {
                assertTrue(res.exceptionOrNull() is IllegalStateException)
            }
        }

        // Also test closing while thumbnail generation is holding the mutex.
        GlitchEngine().use { thumbEngine ->
            val recipes = (1..12).associate { idx -> "preset_$idx" to "filters:\n  - type: Invert" }
            val thumbJob = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                thumbEngine.generateThumbnails(width, height, frame, recipes)
            }
            thumbEngine.close()
            thumbJob.await()
            thumbEngine.awaitCloseForTest()
            assertTrue(thumbEngine.processFrame(width, height, frame).isFailure)
        }

        val afterProcess = engine.processFrame(width, height, frame)
        assertTrue(afterProcess.isFailure)
        assertTrue(afterProcess.exceptionOrNull() is IllegalStateException)

        val afterSnap = engine.captureSnapshotPng()
        assertTrue(afterSnap.isFailure)
        assertTrue(afterSnap.exceptionOrNull() is IllegalStateException)

        assertTrue(engine.generateThumbnails(width, height, frame, mapOf("inv" to "filters:\n  - type: Invert")).isEmpty())
        engine.updateConfig(seed = 99uL)

        val filterErr = runCatching { engine.availablePngFilters() }.exceptionOrNull()
        assertTrue(filterErr is IllegalStateException)
        val validateErr = runCatching { engine.validate("filters:\n  - type: Invert") }.exceptionOrNull()
        assertTrue(validateErr is IllegalStateException)
    }

    @Test
    fun `processFrameWithPass and captureSnapshotPng handle all four passes accurately`() = runBlocking {
        val width = 16
        val height = 8
        val frame = createTestFrame(width, height, alpha = 0x55.toByte())
        val invertRecipe = """
            filters:
              - type: Regenerate
                strategy:
                  fixed: none
              - type: Invert
        """.trimIndent()

        // Left half: foreground (255), x == 8..9: soft edge (128), right remainder: background (0)
        val weights = ByteArray(width * height) { idx ->
            val x = idx % width
            when {
                x < 8 -> 255.toByte()
                x < 10 -> 128.toByte()
                else -> 0.toByte()
            }
        }
        var segmentCalls = 0
        val fakeSegmenter = FrameSegmenter { w, h, _ ->
            segmentCalls++
            assertEquals(width, w)
            assertEquals(height, h)
            ForegroundMask(w, h, weights)
        }

        GlitchEngine().use { engine ->
            engine.updateConfig(
                recipeYaml = invertRecipe,
                seed = 42uL,
                seedMode = SeedMode.FIXED,
                filterOption = PngFilterOption.PAETH,
            )

            // 1. NONE pass before any liveSession initialization
            val noneOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.NONE, fakeSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.INACTIVE, noneOut.segmentationStatus)
            assertEquals(0, segmentCalls)
            for (i in frame.indices step 4) {
                assertEquals(frame[i], noneOut.rgbaBytes[i])
                assertEquals(frame[i + 1], noneOut.rgbaBytes[i + 1])
                assertEquals(frame[i + 2], noneOut.rgbaBytes[i + 2])
                assertEquals(0xFF.toByte(), noneOut.rgbaBytes[i + 3])
            }
            val nonePng = engine.captureSnapshotPng().getOrThrow()
            assertArrayEquals(noneOut.rgbaBytes, decodePngToRgba(nonePng))

            // 2. FOREGROUND pass (verifying R, G, and B channels)
            val fgOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, fakeSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.DETECTED, fgOut.segmentationStatus)
            assertEquals(1, segmentCalls)
            assertAllPixelsOpaque(fgOut.rgbaBytes)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val p = y * width + x
                    val off = p * 4
                    val m = weights[p].toInt() and 0xFF
                    for (c in 0 until 3) {
                        val orig = frame[off + c].toInt() and 0xFF
                        val inv = 255 - orig
                        val expected = (orig * (255 - m) + inv * m + 127) / 255
                        assertEquals(expected, fgOut.rgbaBytes[off + c].toInt() and 0xFF)
                    }
                }
            }
            val fgPng = engine.captureSnapshotPng().getOrThrow()
            assertArrayEquals(fgOut.rgbaBytes, decodePngToRgba(fgPng))

            // 3. BACKGROUND pass (verifying R, G, and B channels)
            val bgOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.BACKGROUND, fakeSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.DETECTED, bgOut.segmentationStatus)
            assertEquals(2, segmentCalls)
            assertAllPixelsOpaque(bgOut.rgbaBytes)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val p = y * width + x
                    val off = p * 4
                    val m = 255 - (weights[p].toInt() and 0xFF)
                    for (c in 0 until 3) {
                        val orig = frame[off + c].toInt() and 0xFF
                        val inv = 255 - orig
                        val expected = (orig * (255 - m) + inv * m + 127) / 255
                        assertEquals(expected, bgOut.rgbaBytes[off + c].toInt() and 0xFF)
                    }
                }
            }
            val bgPng = engine.captureSnapshotPng().getOrThrow()
            assertArrayEquals(bgOut.rgbaBytes, decodePngToRgba(bgPng))

            // 4. ALL pass skips segmentation and switches snapshot back to liveSession
            val allOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.ALL, fakeSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.INACTIVE, allOut.segmentationStatus)
            assertEquals(2, segmentCalls)
            assertAllPixelsOpaque(allOut.rgbaBytes)
            for (i in frame.indices step 4) {
                for (c in 0 until 3) {
                    assertEquals(255 - (frame[i + c].toInt() and 0xFF), allOut.rgbaBytes[i + c].toInt() and 0xFF)
                }
            }
            val allPng = engine.captureSnapshotPng().getOrThrow()
            assertArrayEquals(allOut.rgbaBytes, decodePngToRgba(allPng))

            // 5. No-subject mask reports NO_SUBJECT
            val emptyMaskSegmenter = FrameSegmenter { w, h, _ ->
                ForegroundMask(w, h, ByteArray(w * h))
            }
            val noSubjectOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, emptyMaskSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.NO_SUBJECT, noSubjectOut.segmentationStatus)

            // 6. Fallback when segmenter is null, returns null, or throws
            val nullSegOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, null).getOrThrow()
            assertEquals(SegmentationStatus.UNAVAILABLE, nullSegOut.segmentationStatus)
            assertArrayEquals(allOut.rgbaBytes, nullSegOut.rgbaBytes)

            val nullResultSegmenter = FrameSegmenter { _, _, _ -> null }
            val nullResultOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.BACKGROUND, nullResultSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.UNAVAILABLE, nullResultOut.segmentationStatus)
            assertArrayEquals(allOut.rgbaBytes, nullResultOut.rgbaBytes)

            val failingSegmenter = FrameSegmenter { _, _, _ -> error("Simulated segmentation error") }
            val fallbackOut = engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, failingSegmenter).getOrThrow()
            assertEquals(SegmentationStatus.UNAVAILABLE, fallbackOut.segmentationStatus)
            assertArrayEquals(allOut.rgbaBytes, fallbackOut.rgbaBytes)
        }
    }

    @Test
    fun `processFrameWithPass validates inputs, cancels segmentation on Wasm failure, and serializes with close and snapshot`() = runBlocking {
        val width = 16
        val height = 8
        val frame = createTestFrame(width, height)
        val mask = ForegroundMask(width, height, ByteArray(width * height) { 255.toByte() })

        GlitchEngine().use { engine ->
            // Invalid input dimensions / lengths
            assertTrue(engine.processFrameWithPass(0, height, frame, EffectTargetPass.NONE).isFailure)
            assertTrue(engine.processFrameWithPass(-2, -2, ByteArray(16), EffectTargetPass.FOREGROUND).isFailure)
            assertTrue(engine.encodeRgbaToPng(-2, -2, ByteArray(16)).isFailure)
            assertTrue(engine.generateThumbnails(-2, -2, ByteArray(16), mapOf("inv" to "filters:\n  - type: Invert")).isEmpty())

            // Invalid recipe should fail and cancel in-flight segmentation cleanly
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val slowSegmenter = FrameSegmenter { _, _, _ ->
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    cancelled.set(true)
                }
                mask
            }
            engine.updateConfig(recipeYaml = "not: valid: yaml: [")
            val invalidRes = engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, slowSegmenter)
            assertTrue(invalidRes.isFailure)

            // Valid recipe and concurrent passes + snapshot + close
            engine.updateConfig(
                recipeYaml = "filters:\n  - type: Invert",
                seed = 42uL,
                seedMode = SeedMode.FIXED,
                filterOption = PngFilterOption.NONE,
            )
            val fastSegmenter = FrameSegmenter { _, _, _ -> mask }
            val jobs = listOf(
                async(Dispatchers.Default) {
                    engine.processFrameWithPass(width, height, frame, EffectTargetPass.FOREGROUND, fastSegmenter)
                },
                async(Dispatchers.Default) {
                    engine.processFrameWithPass(width, height, frame, EffectTargetPass.NONE, fastSegmenter)
                },
                async(Dispatchers.Default) {
                    engine.processFrameWithPass(width, height, frame, EffectTargetPass.BACKGROUND, fastSegmenter)
                },
                async(Dispatchers.Default) {
                    engine.captureSnapshotPng()
                },
            )
            engine.close()
            val results = jobs.awaitAll()
            engine.awaitCloseForTest()
            for (res in results) {
                if (res.isFailure) {
                    val ex = res.exceptionOrNull()
                    assertTrue("Unexpected exception type: $ex", ex is IllegalStateException)
                }
            }

            // All passes reject calls after close
            for (pass in EffectTargetPass.entries) {
                val afterClose = engine.processFrameWithPass(width, height, frame, pass, fastSegmenter)
                assertTrue(afterClose.isFailure)
                assertTrue(afterClose.exceptionOrNull() is IllegalStateException)
            }
            assertTrue(engine.encodeRgbaToPng(width, height, frame).isFailure)
        }
    }

    private suspend fun decodePngToRgba(pngBytes: ByteArray): ByteArray {
        val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(pngBytes.size > 8 && pngBytes.sliceArray(0 until 8).contentEquals(expectedSig))
        assertEquals("Expected RGB PNG IHDR color type (2)", 2, pngBytes[25].toInt() and 0xFF)
        openSession(PngglitchComponent).use { session ->
            context(session) {
                val live = when (val created = LiveSession.fromPng(pngBytes)) {
                    is WitResult.Ok -> created.value
                    is WitResult.Err -> error("Failed to decode PNG: ${created.error}")
                }
                try {
                    return when (val rendered = live.renderRgba()) {
                        is WitResult.Ok -> rendered.value
                        is WitResult.Err -> error("Failed to render PNG: ${rendered.error}")
                    }
                } finally {
                    live.close()
                }
            }
        }
    }

    private fun countChangedPixels(before: ByteArray, after: ByteArray): Int {
        var changed = 0
        for (i in before.indices step 4) {
            if (before[i] != after[i] || before[i + 1] != after[i + 1] || before[i + 2] != after[i + 2]) {
                changed++
            }
        }
        return changed
    }
}
