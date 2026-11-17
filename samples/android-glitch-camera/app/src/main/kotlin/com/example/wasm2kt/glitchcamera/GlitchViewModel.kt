package com.example.wasm2kt.glitchcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.FilterInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The size frames are processed at. The camera is asked for at least this size and each frame is
 * downscaled to fit within [longSide] x [shortSide] before it reaches the glitch engine, so the
 * cost of a frame never exceeds the selected option. The label is the short side.
 */
enum class CameraResolutionOption(
    val label: String,
    val longSide: Int,
    val shortSide: Int,
) {
    SD_360P("360p", 640, 360),
    QHD_540P("540p", 960, 540),
    HD_720P("720p", 1280, 720),
}

enum class CameraZoomOption(
    val unselectedLabel: String,
    val selectedLabel: String,
    val ratio: Float,
) {
    ULTRA_WIDE("0.5", "0.5×", 0.5f),
    WIDE("1", "1×", 1.0f),
    TELE("2", "2×", 2.0f),
}

enum class ControlMode(val label: String) {
    PRESETS("プリセット"),
    SINGLE_FILTER("シングル"),
}

data class GlitchUiState(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val resolution: CameraResolutionOption = CameraResolutionOption.QHD_540P,
    val zoomOption: CameraZoomOption = CameraZoomOption.WIDE,
    val controlMode: ControlMode = ControlMode.PRESETS,
    val selectedPreset: RecipePreset = RecipePresets.curated.first(),
    val availableFilters: List<FilterInfo> = emptyList(),
    val selectedFilter: FilterInfo? = null,
    val filterParams: Map<String, String> = emptyMap(),
    val seedMode: SeedMode = SeedMode.PER_FRAME,
    val seed: ULong = 42uL,
    val pngFilterOption: PngFilterOption = PngFilterOption.DEFAULT,
    val activeRecipeYaml: String = RecipePresets.curated.first().yaml,
    val fallbackPreviewBitmap: Bitmap? = null,
    val presetThumbnails: Map<String, Bitmap> = emptyMap(),
    val filterThumbnails: Map<String, Bitmap> = emptyMap(),
    val isRecording: Boolean = false,
    val isBenchmarkWarmup: Boolean = false,
    val statusMessage: String? = null,
)

/**
 * Statistics of the latest processed camera frame. Kept out of [GlitchUiState] so that a new
 * frame only recomposes what reads it (the FPS badge and the settings sheet's statistics). The
 * frame itself is drawn by [PreviewRenderer], outside Compose.
 */
data class PreviewFrame(
    val frameNumber: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    /** Time spent in the glitch engine (`reloadPixels`, `apply` and `renderRgba`). */
    val processingTimeMs: Double = 0.0,
    /** Frames shown per second, smoothed over recent frames (0 until two frames were shown). */
    val displayFps: Double = 0.0,
)

class GlitchViewModel : ViewModel() {
    private val engine = GlitchEngine()

    /*
     * Frames go through three stages, so that a frame's cost is that of the slowest stage rather
     * than the sum of all three:
     *
     * - The conversion stage is the camera's analyzer ([analyzeImage], on [analysisExecutor]): it
     *   converts every camera frame as it arrives, closes it at once (so the camera can deliver the
     *   next one), and leaves the result in [latestFrame]. A frame the engine has not taken yet is
     *   replaced by the newer one. So when the engine finishes a frame, the next one is already
     *   converted, instead of the engine waiting up to a camera frame interval for it and then for
     *   its conversion.
     * - The engine stage is one long-running coroutine ([engineLoop]) that takes the latest
     *   converted frame and awaits the glitch engine, whose suspending calls run on the
     *   component's own scheduler thread.
     * - The presentation stage runs on its own thread ([presentationExecutor]): it copies the
     *   result into the preview Bitmap, draws it, and hands it to the MP4 encoder. It owns the
     *   Bitmap and the preview surface.
     *
     * The converted frames' buffers come from [framePool], so a frame is never converted into the
     * buffer the engine is reading. `renderRgba` returns a new array for every frame, so the engine
     * stage hands its output over without copying it ([handOff]). Only the latest output waits: if
     * the presentation thread is still busy, an output it has not taken yet is replaced by the
     * newer one.
     */
    val analysisExecutor: Executor = Dispatchers.Default.asExecutor()
    private val presentationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glitch-present").apply { isDaemon = true }
    }
    private val pendingOutput = AtomicReference<GlitchFrameOutput?>(null)

    private val framePool = FrameBufferPool()
    private val latestFrame = LatestFrameSlot(framePool)

    /** Signals [engineLoop] that [latestFrame] may hold a frame. */
    private val frameReady = Channel<Unit>(Channel.CONFLATED)

    // CameraX calls the analyzer for one frame at a time (the next frame comes after the previous
    // one is closed), so the camera's conversion scratch is never used concurrently.
    private val cameraScratch = RgbaBufferHolder()

    // Used by the synthetic run's conversion coroutine only.
    private val syntheticScratch = RgbaBufferHolder()
    private val baseAssetsLoaded = AtomicBoolean(false)

    /** Draws frames into the preview surface (AndroidExternalSurface) on the presentation thread. */
    val previewRenderer = PreviewRenderer(presentationExecutor)

    // One Bitmap is enough: it is written and drawn on the presentation thread only (see PreviewRenderer).
    private var previewBitmap: Bitmap? = null

    private val recorderLock = Any()
    private var activeRecorder: Mp4VideoRecorder? = null
    private val recorderCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(GlitchUiState())
    val uiState: StateFlow<GlitchUiState> = _uiState.asStateFlow()

    private val _previewFrame = MutableStateFlow(PreviewFrame())
    val previewFrame: StateFlow<PreviewFrame> = _previewFrame.asStateFlow()

    // Only touched on the presentation thread.
    private var lastFrameNanos: Long = 0L
    private var smoothedFrameIntervalMs: Double = 0.0

    init {
        viewModelScope.launch(Dispatchers.Default) { engineLoop() }
        viewModelScope.launch {
            val filters = runCatching { engine.availablePngFilters() }.getOrDefault(emptyList())
            val defaultFilter = filters.firstOrNull { it.name == "chromatic-aberration" } ?: filters.firstOrNull()
            val defaultParams = defaultFilter?.let { RecipePresets.defaultParamsFor(it) } ?: emptyMap()
            _uiState.update { state ->
                state.copy(
                    availableFilters = filters,
                    selectedFilter = defaultFilter,
                    filterParams = defaultParams,
                )
            }
        }
    }

    fun ensureBaseAssetsLoaded(context: Context) {
        if (!baseAssetsLoaded.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.Default) {
            val decoded = runCatching {
                BitmapFactory.decodeResource(appContext.resources, R.drawable.sample_preview_base)
            }.getOrNull() ?: return@launch

            val argbBitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)
            }
            _uiState.update { it.copy(fallbackPreviewBitmap = argbBitmap) }
            postToPresentation { previewRenderer.setFallback(argbBitmap) }

            val thumbWidth = 84
            val thumbHeight = 84
            val scaledThumb = Bitmap.createScaledBitmap(argbBitmap, thumbWidth, thumbHeight, true)
            val baseRgba = ByteArray(thumbWidth * thumbHeight * 4)
            scaledThumb.copyPixelsToBuffer(ByteBuffer.wrap(baseRgba))

            val presetRecipeMap = LinkedHashMap<String, String>()
            for (preset in RecipePresets.curated) {
                presetRecipeMap[preset.id] = preset.yaml
            }
            val presetRgbaMap = runCatching {
                engine.generateThumbnails(thumbWidth, thumbHeight, baseRgba, presetRecipeMap)
            }.getOrDefault(emptyMap())

            val presetBitmaps = LinkedHashMap<String, Bitmap>(presetRgbaMap.size)
            for ((id, rgba) in presetRgbaMap) {
                val bmp = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                presetBitmaps[id] = bmp
            }
            _uiState.update { it.copy(presetThumbnails = presetBitmaps) }

            val filters = _uiState.value.availableFilters.ifEmpty {
                runCatching { engine.availablePngFilters() }.getOrDefault(emptyList())
            }
            val filterRecipeMap = LinkedHashMap<String, String>()
            for (filter in filters) {
                val defaultParams = RecipePresets.defaultParamsFor(filter)
                filterRecipeMap[filter.name] = RecipePresets.buildSingleFilterYaml(filter, defaultParams)
            }
            val filterRgbaMap = runCatching {
                engine.generateThumbnails(thumbWidth, thumbHeight, baseRgba, filterRecipeMap)
            }.getOrDefault(emptyMap())

            val filterBitmaps = LinkedHashMap<String, Bitmap>(filterRgbaMap.size)
            for ((name, rgba) in filterRgbaMap) {
                val bmp = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                filterBitmaps[name] = bmp
            }
            _uiState.update { it.copy(filterThumbnails = filterBitmaps) }
        }
    }

    /**
     * Runs a synthetic 160x120 warmup sequence through [GlitchEngine] (`fromPixels`, `reloadPixels`,
     * `apply`, `renderRgba`, and `snapshot`) in a coroutine so Baseline Profile generation
     * captures the AOT-compiled `png-glitch-component` hot path even without a physical camera stream.
     */
    fun runBenchmarkWarmup() {
        _uiState.update { it.copy(isBenchmarkWarmup = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val width = 160
            val height = 120
            val syntheticRgba = ByteArray(width * height * 4) { idx ->
                when (idx % 4) {
                    0 -> ((idx / 4) % 256).toByte()
                    1 -> ((idx / 7) % 256).toByte()
                    2 -> ((idx / 11) % 256).toByte()
                    else -> 0xFF.toByte()
                }
            }
            for (preset in RecipePresets.curated.take(3)) {
                engine.updateConfig(
                    recipeYaml = preset.yaml,
                    seed = 42uL,
                    seedMode = SeedMode.PER_FRAME,
                    // AdaptiveFast tries all five filters, so the profile covers each of them.
                    filterOption = PngFilterOption.ADAPTIVE_FAST,
                )
                repeat(3) {
                    engine.processFrame(width, height, syntheticRgba).onSuccess(::handOff)
                }
            }
            engine.captureSnapshotPng()
        }
    }

    /**
     * Feeds [frameCount] synthetic camera frames through the same pipeline as the camera, as fast
     * as the engine takes them, at the processing size [resolution]. Used by
     * `FramePipelineBenchmark`, which measures the [FrameTrace] sections and waits for
     * [SYNTHETIC_RUN_DONE] in the status message.
     *
     * The source imitates a back camera on a phone held upright: a 1280x720 RGBA_8888 plane with
     * row padding, rotated by 90 degrees, so the conversion does the same work as for the camera.
     */
    fun runSyntheticFrames(frameCount: Int, resolution: CameraResolutionOption) {
        _uiState.update { it.copy(isBenchmarkWarmup = true, resolution = resolution) }
        viewModelScope.launch(Dispatchers.Default) {
            val srcWidth = 1280
            val srcHeight = 720
            val rowStride = srcWidth * 4 + 64
            val source = ByteBuffer.allocateDirect(rowStride * srcHeight)
            for (y in 0 until srcHeight) {
                for (x in 0 until srcWidth) {
                    val at = y * rowStride + x * 4
                    source.put(at, (x * 255 / srcWidth).toByte())
                    source.put(at + 1, (y * 255 / srcHeight).toByte())
                    source.put(at + 2, ((x + y) % 256).toByte())
                    source.put(at + 3, 0xFF.toByte())
                }
            }
            val preset = RecipePresets.curated.first()
            engine.updateConfig(
                recipeYaml = preset.yaml,
                seed = 42uL,
                seedMode = SeedMode.PER_FRAME,
                filterOption = PngFilterOption.DEFAULT,
            )
            // Conversion overlaps the engine as on the camera path, but no frame is dropped: the
            // converter waits while a converted frame is still queued (capacity 1).
            val frames = Channel<ExtractedFrame>(capacity = 1)
            FrameTrace.asyncSection(FrameTrace.SYNTHETIC_RUN) {
                coroutineScope {
                    launch {
                        try {
                            repeat(frameCount) {
                                val extracted = FrameTrace.section(FrameTrace.CONVERT) {
                                    RgbaFrameTransform.convert(
                                        source = source,
                                        srcWidth = srcWidth,
                                        srcHeight = srcHeight,
                                        rowStride = rowStride,
                                        pixelStride = 4,
                                        rotationDegrees = 90,
                                        mirrorHorizontally = false,
                                        maxLongSide = resolution.longSide,
                                        maxShortSide = resolution.shortSide,
                                        scratch = syntheticScratch,
                                        outputBuffer = framePool::acquire,
                                    )
                                }
                                frames.send(extracted)
                            }
                        } finally {
                            frames.close()
                        }
                    }
                    for (extracted in frames) runEngine(extracted)
                }
            }
            // Runs after the presentation of the last frame handed off above.
            postToPresentation {
                _uiState.update { it.copy(statusMessage = SYNTHETIC_RUN_DONE) }
            }
        }
    }

    /**
     * Conversion stage, the camera's analyzer: converts [image], closes it, and leaves the result
     * for [engineLoop], replacing a frame the engine has not taken yet.
     */
    fun analyzeImage(image: ImageProxy) {
        try {
            val currentState = _uiState.value
            val extracted = FrameTrace.section(FrameTrace.CONVERT) {
                FrameConverter.extractRgba(
                    image = image,
                    mirrorHorizontally = currentState.lensFacing == CameraSelector.LENS_FACING_FRONT,
                    maxLongSide = currentState.resolution.longSide,
                    maxShortSide = currentState.resolution.shortSide,
                    scratchHolder = cameraScratch,
                    outputBuffer = framePool::acquire,
                )
            }
            latestFrame.offer(extracted)
            frameReady.trySend(Unit)
        } catch (t: Throwable) {
            _uiState.update { state ->
                state.copy(statusMessage = t.message ?: "Frame error")
            }
        } finally {
            image.close()
        }
    }

    /** Engine stage: processes the latest converted camera frame whenever there is one. */
    private suspend fun engineLoop() {
        while (true) {
            frameReady.receive()
            while (true) {
                val frame = latestFrame.take() ?: break
                runEngine(frame)
            }
        }
    }

    /**
     * Engine stage: runs the engine on [frame], hands the output off, and gives [frame]'s buffer
     * back to [framePool] (`reloadPixels` has copied it into the component by then).
     */
    private suspend fun runEngine(frame: ExtractedFrame) {
        try {
            // Async: the engine's calls suspend, and may resume on another thread.
            FrameTrace.asyncSection(FrameTrace.ANALYZE) {
                engine.processFrame(frame.width, frame.height, frame.rgbaBytes)
                    .onSuccess(::handOff)
                    .onFailure { err ->
                        _uiState.update { state -> state.copy(statusMessage = err.message ?: "Glitch failed") }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _uiState.update { state -> state.copy(statusMessage = t.message ?: "Frame error") }
        } finally {
            framePool.release(frame.rgbaBytes)
        }
    }

    /** Engine stage: passes [output] to the presentation thread, replacing one it has not taken yet. */
    private fun handOff(output: GlitchFrameOutput) {
        if (pendingOutput.getAndSet(output) == null) postToPresentation(::presentPending)
    }

    /**
     * Runs [block] on the presentation thread. Once [onCleared] shut it down, [block] is dropped
     * (an engine-stage coroutine may still be finishing a frame then).
     */
    private fun postToPresentation(block: () -> Unit) {
        try {
            presentationExecutor.execute(block)
        } catch (e: RejectedExecutionException) {
            // The view model is being cleared.
        }
    }

    /** Presentation thread: shows the latest handed-off output and gives it to the encoder. */
    private fun presentPending() {
        val output = pendingOutput.getAndSet(null) ?: return
        try {
            FrameTrace.section(FrameTrace.PRESENT) { present(output) }
        } catch (t: Throwable) {
            _uiState.update { state -> state.copy(statusMessage = t.message ?: "Preview error") }
        }
    }

    private fun present(output: GlitchFrameOutput) {
        val bitmap = acquireBitmap(output.width, output.height)
        FrameTrace.section(FrameTrace.COPY_TO_BITMAP) {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(output.rgbaBytes))
        }
        // Show the frame before encoding it, so the preview does not wait for the encoder.
        FrameTrace.section(FrameTrace.DRAW) { previewRenderer.render(bitmap) }
        publishStats(output)
        synchronized(recorderLock) {
            val recorder = activeRecorder
            if (recorder != null && recorder.width == output.width && recorder.height == output.height) {
                FrameTrace.section(FrameTrace.ENCODE) {
                    runCatching { recorder.submitFrame(bitmap) }
                }
            }
        }
    }

    fun toggleCameraLens() {
        _uiState.update { state ->
            val nextLens = if (state.lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            state.copy(lensFacing = nextLens)
        }
    }

    fun selectResolution(option: CameraResolutionOption) {
        _uiState.update { state -> state.copy(resolution = option) }
    }

    fun selectZoom(option: CameraZoomOption) {
        _uiState.update { state -> state.copy(zoomOption = option) }
    }

    fun selectControlMode(mode: ControlMode) {
        _uiState.update { state ->
            val yaml = when (mode) {
                ControlMode.PRESETS -> state.selectedPreset.yaml
                ControlMode.SINGLE_FILTER -> {
                    val filter = state.selectedFilter
                    if (filter != null) {
                        RecipePresets.buildSingleFilterYaml(filter, state.filterParams)
                    } else {
                        state.selectedPreset.yaml
                    }
                }
            }
            pushConfigToEngine(
                yaml = yaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(controlMode = mode, activeRecipeYaml = yaml)
        }
    }

    fun selectPreset(preset: RecipePreset) {
        _uiState.update { state ->
            pushConfigToEngine(
                yaml = preset.yaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(
                controlMode = ControlMode.PRESETS,
                selectedPreset = preset,
                activeRecipeYaml = preset.yaml,
            )
        }
    }

    fun selectSingleFilter(filter: FilterInfo) {
        val defaultParams = RecipePresets.defaultParamsFor(filter)
        val yaml = RecipePresets.buildSingleFilterYaml(filter, defaultParams)
        _uiState.update { state ->
            pushConfigToEngine(
                yaml = yaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(
                controlMode = ControlMode.SINGLE_FILTER,
                selectedFilter = filter,
                filterParams = defaultParams,
                activeRecipeYaml = yaml,
            )
        }
    }

    fun updateFilterParam(paramName: String, value: String) {
        _uiState.update { state ->
            val filter = state.selectedFilter ?: return@update state
            val updatedParams = state.filterParams.toMutableMap().apply {
                put(paramName, value)
            }
            val yaml = RecipePresets.buildSingleFilterYaml(filter, updatedParams)
            pushConfigToEngine(
                yaml = yaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(
                filterParams = updatedParams,
                activeRecipeYaml = yaml,
            )
        }
    }

    fun resetFilterParams() {
        _uiState.update { state ->
            val filter = state.selectedFilter ?: return@update state
            val defaults = RecipePresets.defaultParamsFor(filter)
            val yaml = RecipePresets.buildSingleFilterYaml(filter, defaults)
            pushConfigToEngine(
                yaml = yaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(
                filterParams = defaults,
                activeRecipeYaml = yaml,
            )
        }
    }

    fun selectSeedMode(mode: SeedMode) {
        _uiState.update { state ->
            pushConfigToEngine(
                yaml = state.activeRecipeYaml,
                seed = state.seed,
                seedMode = mode,
                filterOption = state.pngFilterOption,
            )
            state.copy(seedMode = mode)
        }
    }

    fun randomizeSeed() {
        val nextSeed = (System.nanoTime().toULong() and 0xFFFF_FFFFuL)
        _uiState.update { state ->
            pushConfigToEngine(
                yaml = state.activeRecipeYaml,
                seed = nextSeed,
                seedMode = state.seedMode,
                filterOption = state.pngFilterOption,
            )
            state.copy(seed = nextSeed)
        }
    }

    fun selectPngFilterOption(option: PngFilterOption) {
        _uiState.update { state ->
            pushConfigToEngine(
                yaml = state.activeRecipeYaml,
                seed = state.seed,
                seedMode = state.seedMode,
                filterOption = option,
            )
            state.copy(pngFilterOption = option)
        }
    }

    fun captureStillPng(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val snapResult = engine.captureSnapshotPng()
            snapResult.onSuccess { pngBytes ->
                val saveResult = withContext(Dispatchers.IO) {
                    MediaStoreHelper.savePngSnapshot(appContext, pngBytes)
                }
                _uiState.update { state ->
                    state.copy(
                        statusMessage = saveResult.fold(
                            onSuccess = { "Saved PNG (${pngBytes.size / 1024} KB) to Pictures/GlitchCamera" },
                            onFailure = { "PNG save failed: ${it.message}" },
                        ),
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(statusMessage = "Snapshot failed: ${err.message}")
                }
            }
        }
    }

    fun toggleVideoRecording(context: Context) {
        val appContext = context.applicationContext
        synchronized(recorderLock) {
            val existing = activeRecorder
            if (existing != null) {
                activeRecorder = null
                _uiState.update { it.copy(isRecording = false, statusMessage = "Finalizing MP4...") }
                // Finalize on an independent IO scope so leaving the screen after pressing Stop
                // still finishes writing and deletes temporary cache files cleanly.
                recorderCleanupScope.launch {
                    try {
                        val finishResult = runCatching { existing.finish() }
                        val file = finishResult.getOrNull()
                        if (file != null && file.exists() && file.length() > 0L) {
                            val saveRes = MediaStoreHelper.saveMp4Video(appContext, file)
                            _uiState.update { state ->
                                state.copy(
                                    statusMessage = saveRes.fold(
                                        onSuccess = { "Saved MP4 video to Movies/GlitchCamera" },
                                        onFailure = { "MP4 save failed: ${it.message}" },
                                    ),
                                )
                            }
                        } else {
                            val failure = finishResult.exceptionOrNull()
                            _uiState.update { state ->
                                state.copy(
                                    statusMessage = if (failure != null) {
                                        "Recording failed: ${failure.message}"
                                    } else {
                                        "Recording stopped"
                                    },
                                )
                            }
                        }
                    } finally {
                        runCatching { existing.close() }
                        runCatching { existing.outputFile.delete() }
                    }
                }
            } else {
                val state = _uiState.value
                val frame = _previewFrame.value
                val recWidth = if (frame.width > 0) frame.width else state.resolution.shortSide
                val recHeight = if (frame.height > 0) frame.height else state.resolution.longSide
                val tempFile = File(appContext.cacheDir, "glitch_rec_${System.currentTimeMillis()}.mp4")
                runCatching {
                    Mp4VideoRecorder(
                        outputFile = tempFile,
                        width = recWidth,
                        height = recHeight,
                    )
                }.onSuccess { recorder ->
                    activeRecorder = recorder
                    _uiState.update {
                        it.copy(
                            isRecording = true,
                            statusMessage = "Recording MP4 (${recWidth}x${recHeight})...",
                        )
                    }
                }.onFailure { err ->
                    runCatching { tempFile.delete() }
                    _uiState.update {
                        it.copy(statusMessage = "Failed to start recorder: ${err.message}")
                    }
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun pushConfigToEngine(
        yaml: String,
        seed: ULong,
        seedMode: SeedMode,
        filterOption: PngFilterOption,
    ) {
        viewModelScope.launch {
            engine.updateConfig(
                recipeYaml = yaml,
                seed = seed,
                seedMode = seedMode,
                filterOption = filterOption,
            )
        }
    }

    /** Presentation thread: updates the frame statistics after [output] was drawn. */
    private fun publishStats(output: GlitchFrameOutput) {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val intervalMs = (now - lastFrameNanos) / 1_000_000.0
            // A long gap (camera rebinding, app in the background) restarts the average.
            smoothedFrameIntervalMs = if (smoothedFrameIntervalMs == 0.0 || intervalMs > 1_000.0) {
                intervalMs
            } else {
                smoothedFrameIntervalMs * 0.9 + intervalMs * 0.1
            }
        }
        lastFrameNanos = now
        val previous = _previewFrame.value
        _previewFrame.value = PreviewFrame(
            frameNumber = previous.frameNumber + 1L,
            width = output.width,
            height = output.height,
            processingTimeMs = output.processingTimeMs,
            displayFps = if (smoothedFrameIntervalMs > 0.0) 1000.0 / smoothedFrameIntervalMs else 0.0,
        )
    }

    /** The reused preview Bitmap. Call on the presentation thread. */
    private fun acquireBitmap(width: Int, height: Int): Bitmap {
        val existing = previewBitmap
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        previewBitmap = created
        return created
    }

    override fun onCleared() {
        super.onCleared()
        val recorderToClose = synchronized(recorderLock) {
            activeRecorder.also { activeRecorder = null }
        }
        if (recorderToClose != null) {
            recorderCleanupScope.launch {
                try {
                    runCatching { recorderToClose.close() }
                } finally {
                    runCatching { recorderToClose.outputFile.delete() }
                }
            }
        }
        runCatching { engine.close() }
        presentationExecutor.shutdown()
    }

    companion object {
        /** Status message shown once [runSyntheticFrames] has presented its last frame. */
        const val SYNTHETIC_RUN_DONE = "Synthetic frames done"
    }
}
