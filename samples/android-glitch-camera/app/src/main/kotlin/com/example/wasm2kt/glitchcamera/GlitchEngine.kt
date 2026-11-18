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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScheduled = AtomicBoolean(false)
    @Volatile
    private var closeCleanupJob: Job? = null
    @Volatile
    private var closed: Boolean = false
    private var worldSession: WorldSession? = null
    private var liveSession: LiveSession? = null
    private var latestCompositeOutput: GlitchFrameOutput? = null

    private fun ensureWorldSessionLocked(): WorldSession {
        check(!closed) { "GlitchEngine is closed" }
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
        // Ignore configuration updates once closed; callers racing with shutdown exit quietly.
        if (closed) return@withLock
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

    suspend fun processFrame(width: Int, height: Int, rgba: ByteArray): Result<GlitchFrameOutput> =
        mutex.withLock {
            processFrameLocked(width, height, rgba, clearComposite = true)
        }

    suspend fun processFrameWithPass(
        width: Int,
        height: Int,
        rgba: ByteArray,
        pass: EffectTargetPass,
        segmenter: FrameSegmenter? = null,
    ): Result<GlitchFrameOutput> = mutex.withLock {
        if (closed) {
            return@withLock Result.failure(IllegalStateException("GlitchEngine is closed"))
        }
        validateRgbaInput(width, height, rgba)?.let { return@withLock Result.failure(it) }

        if (!pass.requiresGlitchEngine) {
            val startNanos = System.nanoTime()
            val copied = FrameMaskCompositor.copyOpaqueRgba(rgba)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
            val output = GlitchFrameOutput(
                rgbaBytes = copied,
                width = width,
                height = height,
                processingTimeMs = elapsedMs,
            )
            latestCompositeOutput = output
            return@withLock Result.success(output)
        }

        if (!pass.requiresSegmentation) {
            return@withLock processFrameLocked(width, height, rgba, clearComposite = true)
        }

        val startNanos = System.nanoTime()
        coroutineScope {
            val maskDeferred = segmenter?.let { seg ->
                async(Dispatchers.Default) {
                    try {
                        seg.segment(width, height, rgba)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val glitchResult = processFrameLocked(width, height, rgba, clearComposite = false)
            if (glitchResult.isFailure) {
                maskDeferred?.cancel()
                return@coroutineScope glitchResult
            }
            val mask = maskDeferred?.await()
            val glitchOutput = glitchResult.getOrThrow()
            if (mask != null) {
                FrameTrace.section(FrameTrace.BLEND) {
                    FrameMaskCompositor.blendInPlace(
                        originalRgba = rgba,
                        glitchedRgba = glitchOutput.rgbaBytes,
                        frameWidth = width,
                        frameHeight = height,
                        mask = mask,
                        pass = pass,
                    )
                }
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
            val finalOutput = glitchOutput.copy(processingTimeMs = elapsedMs)
            latestCompositeOutput = finalOutput
            Result.success(finalOutput)
        }
    }

    private suspend fun processFrameLocked(
        width: Int,
        height: Int,
        rgba: ByteArray,
        clearComposite: Boolean,
    ): Result<GlitchFrameOutput> {
        if (closed) {
            return Result.failure(IllegalStateException("GlitchEngine is closed"))
        }
        validateRgbaInput(width, height, rgba)?.let { return Result.failure(it) }
        if (clearComposite) {
            latestCompositeOutput = null
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

    suspend fun encodeRgbaToPng(width: Int, height: Int, rgba: ByteArray): Result<ByteArray> = mutex.withLock {
        if (closed) {
            return Result.failure(IllegalStateException("GlitchEngine is closed"))
        }
        validateRgbaInput(width, height, rgba)?.let { return Result.failure(it) }
        encodeRgbaToPngLocked(width, height, rgba)
    }

    suspend fun captureSnapshotPng(overrideFrame: GlitchFrameOutput? = null): Result<ByteArray> = mutex.withLock {
        if (closed) {
            return Result.failure(IllegalStateException("GlitchEngine is closed"))
        }
        val frameToEncode = overrideFrame ?: latestCompositeOutput
        if (frameToEncode != null) {
            validateRgbaInput(frameToEncode.width, frameToEncode.height, frameToEncode.rgbaBytes)
                ?.let { return Result.failure(it) }
            return encodeRgbaToPngLocked(frameToEncode.width, frameToEncode.height, frameToEncode.rgbaBytes)
        }
        val session = liveSession
            ?: return Result.failure(IllegalStateException("No active camera frame in live session"))
        when (val snapRes = session.snapshot()) {
            is WitResult.Ok -> Result.success(snapRes.value)
            is WitResult.Err -> Result.failure(IllegalStateException(formatError(snapRes.error)))
        }
    }

    private suspend fun encodeRgbaToPngLocked(width: Int, height: Int, rgba: ByteArray): Result<ByteArray> {
        val encoding = Encoding(
            colorType = ColorType.RGB,
            bitDepth = 8u,
            filter = currentFilterOption.strategy,
        )
        val created = context(ensureWorldSessionLocked()) {
            LiveSession.fromPixels(width.toUInt(), height.toUInt(), rgba, encoding)
        }
        val snapSession = when (created) {
            is WitResult.Ok -> created.value
            is WitResult.Err -> return Result.failure(IllegalStateException(formatError(created.error)))
        }
        return try {
            when (val snapRes = snapSession.snapshot()) {
                is WitResult.Ok -> Result.success(snapRes.value)
                is WitResult.Err -> Result.failure(IllegalStateException(formatError(snapRes.error)))
            }
        } finally {
            snapSession.close()
        }
    }

    private fun validateRgbaInput(width: Int, height: Int, rgba: ByteArray): IllegalArgumentException? {
        if (width <= 0 || height <= 0) {
            return IllegalArgumentException("Invalid frame dimensions ${width}x$height")
        }
        val expectedBytesLong = width.toLong() * height.toLong() * 4L
        if (expectedBytesLong > Int.MAX_VALUE.toLong() || rgba.size.toLong() != expectedBytesLong) {
            return IllegalArgumentException("Expected $expectedBytesLong RGBA bytes for ${width}x${height}, got ${rgba.size}")
        }
        return null
    }

    suspend fun generateThumbnails(
        width: Int,
        height: Int,
        baseRgba: ByteArray,
        recipes: Map<String, String>,
        seed: ULong = 42uL,
        filterOption: PngFilterOption? = null,
    ): Map<String, ByteArray> = mutex.withLock {
        if (closed) {
            return emptyMap()
        }
        if (validateRgbaInput(width, height, baseRgba) != null || recipes.isEmpty()) {
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
                if (closed) break
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
        closed = true
        if (mutex.tryLock()) {
            try {
                closeSessionsLocked()
            } finally {
                mutex.unlock()
            }
            return
        }
        // An in-flight worker holds the mutex; finish session disposal once it unlocks so
        // callers on the main thread (such as ViewModel.onCleared) never block.
        if (cleanupScheduled.compareAndSet(false, true)) {
            closeCleanupJob = closeScope.launch {
                mutex.withLock {
                    closeSessionsLocked()
                }
            }
        }
    }

    private fun closeSessionsLocked() {
        latestCompositeOutput = null
        try {
            liveSession?.close()
        } finally {
            liveSession = null
            try {
                worldSession?.close()
            } finally {
                worldSession = null
            }
        }
    }

    internal suspend fun awaitCloseForTest() {
        closeCleanupJob?.join()
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
