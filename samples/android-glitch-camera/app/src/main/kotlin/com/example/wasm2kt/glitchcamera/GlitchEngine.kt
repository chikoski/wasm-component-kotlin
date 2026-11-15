package com.example.wasm2kt.glitchcamera

import com.example.wasm2kt.glitchcamera.pngglitch.PngglitchComponent
import com.example.wasm2kt.glitchcamera.pngglitch.Session as WorldSession
import com.example.wasm2kt.glitchcamera.pngglitch.WitResult
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.FilterInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.Format
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.GlitchError
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.listFilters
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.validateRecipe
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.ColorType
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.Encoding
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.FilterStrategy
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.FilterType
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.Session as LiveSession
import com.example.wasm2kt.glitchcamera.pngglitch.openSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PngFilterOption(
    val label: String,
    val strategy: FilterStrategy,
) {
    ADAPTIVE_FAST("AdaptiveFast", FilterStrategy.AdaptiveFast),
    PAETH("Paeth", FilterStrategy.Fixed(FilterType.PAETH)),
    SUB("Sub", FilterStrategy.Fixed(FilterType.SUB)),
    UP("Up", FilterStrategy.Fixed(FilterType.UP)),
    AVERAGE("Average", FilterStrategy.Fixed(FilterType.AVERAGE)),
    NONE("None", FilterStrategy.Fixed(FilterType.NONE)),
    ;

    companion object {
        /**
         * The strategy used until one is chosen in the settings. `PAETH` populates PNG prediction
         * residuals by default so scanline and filter-tag glitches propagate across rows and
         * columns; recipes requiring raw pixel operations insert `Regenerate` (`fixed: none`)
         * explicitly. Note that `Paeth` adds ~50-75 ms per 540p frame on the host JVM compared to
         * `None`, trading throughput for out-of-the-box differential glitch propagation.
         */
        val DEFAULT: PngFilterOption = PAETH
    }
}

data class GlitchFrameOutput(
    val rgbaBytes: ByteArray,
    val width: Int,
    val height: Int,
    val processingTimeMs: Double,
)

class GlitchEngine : AutoCloseable {
    private val mutex = Mutex()
    private var worldSession: WorldSession? = null
    private var liveSession: LiveSession? = null

    private fun ensureWorldSessionLocked(): WorldSession {
        return worldSession ?: openSession(PngglitchComponent).also { worldSession = it }
    }

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentFilterOption: PngFilterOption = PngFilterOption.DEFAULT

    private var currentRecipeYaml: String = RecipePresets.curated.first().yaml
    private var currentSeed: ULong = 42uL
    private var currentSeedMode: SeedMode = SeedMode.PER_FRAME
    private var recipeDirty: Boolean = true

    suspend fun availablePngFilters(): List<FilterInfo> = mutex.withLock {
        context(ensureWorldSessionLocked()) {
            listFilters().filter { info ->
                !info.deprecated && info.formats.contains(Format.PNG)
            }
        }
    }

    suspend fun validate(recipeYaml: String): GlitchError? = mutex.withLock {
        context(ensureWorldSessionLocked()) {
            when (val res = validateRecipe(recipeYaml)) {
                is WitResult.Ok -> null
                is WitResult.Err -> res.error
            }
        }
    }

    suspend fun updateConfig(
        recipeYaml: String = currentRecipeYaml,
        seed: ULong = currentSeed,
        seedMode: SeedMode = currentSeedMode,
        filterOption: PngFilterOption = currentFilterOption,
    ) = mutex.withLock {
        if (filterOption != currentFilterOption) {
            currentFilterOption = filterOption
            liveSession?.close()
            liveSession = null
        }
        if (recipeYaml != currentRecipeYaml || seed != currentSeed || seedMode != currentSeedMode) {
            currentRecipeYaml = recipeYaml
            currentSeed = seed
            currentSeedMode = seedMode
            recipeDirty = true
        }
    }

    suspend fun processFrame(width: Int, height: Int, rgba: ByteArray): Result<GlitchFrameOutput> = mutex.withLock {
        val expectedBytes = width * height * 4
        if (rgba.size != expectedBytes) {
            return Result.failure(
                IllegalArgumentException("Expected $expectedBytes RGBA bytes for ${width}x${height}, got ${rgba.size}"),
            )
        }
        val startNanos = System.nanoTime()
        val session = ensureLiveSessionLocked(width, height, rgba).getOrElse { return Result.failure(it) }
        if (recipeDirty) {
            val setRes = FrameTrace.asyncSection(FrameTrace.SET_RECIPE) {
                session.setRecipe(currentRecipeYaml, currentSeed, currentSeedMode)
            }
            when (setRes) {
                is WitResult.Ok -> recipeDirty = false
                is WitResult.Err -> return Result.failure(IllegalStateException(formatError(setRes.error)))
            }
        }
        when (val applyRes = FrameTrace.asyncSection(FrameTrace.PROCESS_FRAME) { session.processFrame(rgba) }) {
            is WitResult.Ok -> {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
                return Result.success(
                    GlitchFrameOutput(
                        rgbaBytes = applyRes.value,
                        width = width,
                        height = height,
                        processingTimeMs = elapsedMs,
                    ),
                )
            }
            is WitResult.Err -> return Result.failure(IllegalStateException(formatError(applyRes.error)))
        }
    }

    suspend fun captureSnapshotPng(): Result<ByteArray> = mutex.withLock {
        val session = liveSession
            ?: return Result.failure(IllegalStateException("No active camera frame in live session"))
        when (val snapRes = session.snapshot()) {
            is WitResult.Ok -> Result.success(snapRes.value)
            is WitResult.Err -> Result.failure(IllegalStateException(formatError(snapRes.error)))
        }
    }

    suspend fun generateThumbnails(
        width: Int,
        height: Int,
        baseRgba: ByteArray,
        recipes: Map<String, String>,
        seed: ULong = 42uL,
        filterOption: PngFilterOption? = null,
    ): Map<String, ByteArray> = mutex.withLock {
        val expectedBytes = width * height * 4
        if (baseRgba.size != expectedBytes || recipes.isEmpty()) {
            return emptyMap()
        }
        val resolvedFilterOption = filterOption ?: currentFilterOption
        val encoding = Encoding(
            colorType = ColorType.RGB,
            bitDepth = 8u,
            filter = resolvedFilterOption.strategy,
        )
        val created = context(ensureWorldSessionLocked()) {
            LiveSession.fromPixels(width.toUInt(), height.toUInt(), baseRgba, encoding)
        }
        val thumbSession = when (created) {
            is WitResult.Ok -> created.value
            is WitResult.Err -> return emptyMap()
        }
        val results = LinkedHashMap<String, ByteArray>(recipes.size)
        try {
            for ((key, yaml) in recipes) {
                if (thumbSession.setRecipe(yaml, seed, SeedMode.FIXED) is WitResult.Err) continue
                when (val rendered = thumbSession.processFrame(baseRgba)) {
                    is WitResult.Ok -> results[key] = rendered.value
                    is WitResult.Err -> continue
                }
            }
        } finally {
            thumbSession.close()
        }
        results
    }

    private suspend fun ensureLiveSessionLocked(width: Int, height: Int, rgba: ByteArray): Result<LiveSession> {
        val existing = liveSession
        if (existing != null && !existing.isClosed && currentWidth == width && currentHeight == height) {
            return Result.success(existing)
        }
        existing?.close()
        liveSession = null
        val encoding = Encoding(
            colorType = ColorType.RGB,
            bitDepth = 8u,
            filter = currentFilterOption.strategy,
        )
        val created = FrameTrace.asyncSection(FrameTrace.FROM_PIXELS) {
            context(ensureWorldSessionLocked()) {
                LiveSession.fromPixels(width.toUInt(), height.toUInt(), rgba, encoding)
            }
        }
        return when (created) {
            is WitResult.Ok -> {
                liveSession = created.value
                currentWidth = width
                currentHeight = height
                recipeDirty = true
                Result.success(created.value)
            }
            is WitResult.Err -> Result.failure(IllegalStateException(formatError(created.error)))
        }
    }

    override fun close() {
        liveSession?.close()
        liveSession = null
        worldSession?.close()
        worldSession = null
    }

    companion object {
        fun formatError(error: GlitchError): String = when (error) {
            is GlitchError.InvalidImage -> "Invalid image: ${error.value}"
            is GlitchError.InvalidRecipe -> "Invalid recipe: ${error.value}"
            GlitchError.NoRecipe -> "No recipe configured"
            GlitchError.TooLarge -> "Image exceeds component size limit"
            GlitchError.UnsupportedEncoding -> "Unsupported PNG encoding"
            GlitchError.UnsupportedFormat -> "Unsupported image format"
        }
    }
}
