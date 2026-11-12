package com.example.wasm2kt.glitchcamera

import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlitchEngineTest {
    @Test
    fun `availablePngFilters returns non-deprecated PNG filters and default recipes validate`() {
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
    fun `processFrame and captureSnapshotPng execute live session across multiple frames`() {
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
}
