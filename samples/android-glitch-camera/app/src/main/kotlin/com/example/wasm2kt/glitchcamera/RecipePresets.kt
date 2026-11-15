package com.example.wasm2kt.glitchcamera

import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.FilterInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamKind
import java.util.Locale

data class RecipePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val yaml: String,
)

object RecipePresets {
    // PNG filters in `listFilters` (`Format.PNG`) that read/write raw pixel values rather than
    // PNG prediction residuals or filter tags. WebP-only filters (`alpha-glitch`,
    // `macroblock-glitch`, `lossy-artifact`) never appear in `availablePngFilters()`.
    internal val PIXEL_DOMAIN_FILTERS = setOf(
        "pixel-sort",
        "color-space-glitch",
        "chromatic-aberration",
        "channel-swap",
        "block-scramble",
        "color-distortion",
        "invert",
        "brighten",
        "darken",
        "shift-channels",
        "bitwise",
    )

    val curated: List<RecipePreset> = listOf(
        RecipePreset(
            id = "rgb_drift",
            title = "RGB Drift",
            subtitle = "Sub filter drift + chromatic shift",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: none
                  - type: ChromaticAberration
                    magnitude: 0.45
                    r_offset: 6
                    g_offset: 0
                    b_offset: -6
                  - type: Regenerate
                    strategy:
                      fixed: sub
                  - type: HorizontalShift
                    magnitude: 0.18
                  - type: Replace
                    magnitude: 0.01
            """.trimIndent(),
        ),
        RecipePreset(
            id = "paeth_melt",
            title = "Paeth Melt",
            subtitle = "Paeth residual corruption + filter-tag melt",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: paeth
                  - type: Replace
                    magnitude: 0.02
                  - type: Transpose
                    magnitude: 0.08
                  - type: ChangeFilterType
                    magnitude: 0.15
            """.trimIndent(),
        ),
        RecipePreset(
            id = "cyber_sort",
            title = "Cyber Sort",
            subtitle = "Pixel sorting + channel swap",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: none
                  - type: PixelSort
                    magnitude: 0.25
                    criterion: brightness
                  - type: ChannelSwap
                    magnitude: 0.18
                    target: br
            """.trimIndent(),
        ),
        RecipePreset(
            id = "block_chaos",
            title = "Block Chaos",
            subtitle = "Block scramble + Sub scanline copy",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: none
                  - type: BlockScramble
                    magnitude: 0.22
                    block_size: 24
                  - type: Regenerate
                    strategy:
                      fixed: sub
                  - type: RandomCopy
                    times: 12
            """.trimIndent(),
        ),
        RecipePreset(
            id = "neon_hsl",
            title = "Neon HSL",
            subtitle = "HSL hue rotation + color distortion",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: none
                  - type: ColorSpaceGlitch
                    magnitude: 0.35
                    hue_shift: 110.0
                    saturation_mult: 1.6
                    lightness_mult: 1.05
                  - type: ColorDistortion
                    magnitude: 0.20
                    strength: 28
            """.trimIndent(),
        ),
        RecipePreset(
            id = "re_encode_cascade",
            title = "Cascade",
            subtitle = "Two-generation Sub to Up filter cascade",
            yaml = """
                filters:
                  - type: Regenerate
                    strategy:
                      fixed: sub
                  - type: Replace
                    magnitude: 0.02
                  - type: HorizontalShift
                    magnitude: 0.08
                  - type: Regenerate
                    strategy:
                      fixed: up
                  - type: Transpose
                    magnitude: 0.06
                  - type: ChangeFilterType
                    magnitude: 0.12
            """.trimIndent(),
        ),
    )

    fun defaultParamsFor(filter: FilterInfo): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (param in filter.params) {
            result[param.name] = defaultParamValue(filter, param)
        }
        return result
    }

    fun defaultParamValue(filter: FilterInfo, param: ParamInfo): String {
        param.default?.let { return it }
        return when (param.kind) {
            ParamKind.FLOAT -> when (param.name) {
                "magnitude" -> "0.25"
                else -> formatFloat(param.min ?: 0.5)
            }
            ParamKind.INT -> when (param.name) {
                "strength" -> "32"
                "times" -> "12"
                "index" -> "0"
                "value" -> "128"
                "r" -> "20"
                "g" -> "0"
                "b" -> "-20"
                else -> (param.min?.toInt() ?: 10).toString()
            }
            ParamKind.ENUM -> param.choices.firstOrNull() ?: ""
            ParamKind.STRATEGY -> "paeth"
        }
    }

    fun sliderRangeFor(param: ParamInfo): ClosedFloatingPointRange<Float> {
        val min = when (param.name) {
            "r", "g", "b" -> -128f
            "r_offset", "g_offset", "b_offset" -> -32f
            "hue_shift" -> -180f
            "saturation_mult", "lightness_mult" -> 0f
            "block_size" -> 4f
            else -> (param.min ?: 0.0).toFloat()
        }
        val max = when (param.name) {
            "strength" -> 160f
            "r", "g", "b" -> 128f
            "r_offset", "g_offset", "b_offset" -> 32f
            "hue_shift" -> 180f
            "saturation_mult", "lightness_mult" -> 3f
            "block_size" -> 64f
            "times" -> 60f
            "index" -> 64f
            "value" -> 255f
            else -> (param.max ?: 1.0).toFloat()
        }
        return min..max
    }

    fun buildSingleFilterYaml(filter: FilterInfo, params: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("filters:\n")
        if (filter.name in PIXEL_DOMAIN_FILTERS) {
            sb.append("  - type: Regenerate\n")
            sb.append("    strategy:\n")
            sb.append("      fixed: none\n")
        }
        sb.append("  - type: ").append(filter.yamlType).append('\n')
        for (param in filter.params) {
            val rawValue = params[param.name] ?: defaultParamValue(filter, param)
            when (param.kind) {
                ParamKind.FLOAT -> {
                    val number = rawValue.toDoubleOrNull() ?: 0.25
                    sb.append("    ").append(param.name).append(": ")
                        .append(formatFloat(number)).append('\n')
                }
                ParamKind.INT -> {
                    val number = rawValue.toDoubleOrNull()?.toInt() ?: 0
                    sb.append("    ").append(param.name).append(": ")
                        .append(number).append('\n')
                }
                ParamKind.ENUM -> {
                    sb.append("    ").append(param.name).append(": ")
                        .append(rawValue).append('\n')
                }
                ParamKind.STRATEGY -> {
                    if (rawValue == "adaptive" || rawValue == "adaptive-fast") {
                        sb.append("    ").append(param.name).append(": ")
                            .append(rawValue).append('\n')
                    } else {
                        sb.append("    ").append(param.name).append(":\n")
                        sb.append("      fixed: ").append(rawValue).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    fun formatFloat(value: Double): String =
        String.format(Locale.US, "%.2f", value)
}
