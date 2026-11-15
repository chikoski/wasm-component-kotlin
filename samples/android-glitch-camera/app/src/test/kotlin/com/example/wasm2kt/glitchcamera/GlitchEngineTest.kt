package com.example.wasm2kt.glitchcamera

import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
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
