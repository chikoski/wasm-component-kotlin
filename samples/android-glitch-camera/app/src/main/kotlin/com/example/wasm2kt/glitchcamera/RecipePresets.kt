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
    val curated: List<RecipePreset> = listOf(
        RecipePreset(
            id = "rgb_drift",
            title = "RGB Drift",
            subtitle = "Chromatic aberration + scanline shift",
            yaml = """
                filters:
                  - type: ChromaticAberration
                    magnitude: 0.45
                    r_offset: 6
                    g_offset: 0
                    b_offset: -6
                  - type: HorizontalShift
                    magnitude: 0.12
            """.trimIndent(),
        ),
        RecipePreset(
            id = "paeth_melt",
            title = "Paeth Melt",
            subtitle = "PNG filter-tag overwrite + byte corruption",
            yaml = """
                filters:
                  - type: ChangeFilterType
                    magnitude: 0.25
                  - type: Replace
                    magnitude: 0.04
                  - type: Transpose
                    magnitude: 0.08
            """.trimIndent(),
        ),
        RecipePreset(
            id = "cyber_sort",
            title = "Cyber Sort",
            subtitle = "Pixel sorting + channel swap",
            yaml = """
                filters:
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
            subtitle = "Block scramble + scanline copy",
            yaml = """
                filters:
                  - type: BlockScramble
                    magnitude: 0.22
                    block_size: 24
                  - type: RandomCopy
                    times: 12
            """.trimIndent(),
        ),
        RecipePreset(
            id = "neon_hsl",
            title = "Neon HSL",
            subtitle = "HSL hue rotation + bitwise XOR",
            yaml = """
                filters:
                  - type: ColorSpaceGlitch
                    magnitude: 0.35
                    hue_shift: 110.0
                    saturation_mult: 1.6
                    lightness_mult: 1.05
                  - type: Bitwise
                    magnitude: 0.06
                    op: xor
                    value: 128
            """.trimIndent(),
        ),
        RecipePreset(
            id = "re_encode_cascade",
            title = "Cascade",
            subtitle = "Two-generation PNG filter cascade",
            yaml = """
                filters:
                  - type: Replace
                    magnitude: 0.03
                  - type: Regenerate
                    strategy:
                      fixed: up
                  - type: HorizontalShift
                    magnitude: 0.10
                  - type: ChromaticAberration
                    magnitude: 0.30
                    r_offset: 4
                    g_offset: 0
                    b_offset: -4
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
