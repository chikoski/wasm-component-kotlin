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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class CameraResolutionOption(
    val label: String,
    val width: Int,
    val height: Int,
) {
    HD_720P("720p", 640, 480),
    FHD_1080P("1080p", 1280, 720),
    UHD_4K("4K", 1280, 960),
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
    val resolution: CameraResolutionOption = CameraResolutionOption.FHD_1080P,
    val zoomOption: CameraZoomOption = CameraZoomOption.WIDE,
    val controlMode: ControlMode = ControlMode.PRESETS,
    val selectedPreset: RecipePreset = RecipePresets.curated.first(),
    val availableFilters: List<FilterInfo> = emptyList(),
    val selectedFilter: FilterInfo? = null,
    val filterParams: Map<String, String> = emptyMap(),
    val seedMode: SeedMode = SeedMode.PER_FRAME,
    val seed: ULong = 42uL,
    val pngFilterOption: PngFilterOption = PngFilterOption.ADAPTIVE_FAST,
    val activeRecipeYaml: String = RecipePresets.curated.first().yaml,
    val previewBitmap: Bitmap? = null,
    val fallbackPreviewBitmap: Bitmap? = null,
    val presetThumbnails: Map<String, Bitmap> = emptyMap(),
    val filterThumbnails: Map<String, Bitmap> = emptyMap(),
    val frameNumber: Long = 0L,
    val processingTimeMs: Double = 0.0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val isRecording: Boolean = false,
    val isBenchmarkWarmup: Boolean = false,
    val statusMessage: String? = null,
)

class GlitchViewModel : ViewModel() {
    private val engine = GlitchEngine()
    val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glitch-camera-worker").apply { isDaemon = true }
    }
    private val scratchHolder = RgbaBufferHolder()
    private val processingFrame = AtomicBoolean(false)
    private val baseAssetsLoaded = AtomicBoolean(false)

    // Double-buffered bitmaps so the UI thread reads one while the worker thread writes the other.
    private var bitmapPool: Array<Bitmap?> = arrayOfNulls(2)
    private var bitmapPoolIndex: Int = 0

    private val recorderLock = Any()
    private var activeRecorder: Mp4VideoRecorder? = null

    private val _uiState = MutableStateFlow(GlitchUiState())
    val uiState: StateFlow<GlitchUiState> = _uiState.asStateFlow()

    init {
        analysisExecutor.execute {
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
        analysisExecutor.execute {
            val decoded = runCatching {
                BitmapFactory.decodeResource(appContext.resources, R.drawable.sample_preview_base)
            }.getOrNull() ?: return@execute

            val argbBitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)
            }
            _uiState.update { it.copy(fallbackPreviewBitmap = argbBitmap) }

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
     * `apply`, `renderRgba`, and `snapshot`) on the worker thread so Baseline Profile generation
     * captures the AOT-compiled `png-glitch-component` hot path even without a physical camera stream.
     */
    fun runBenchmarkWarmup() {
        _uiState.update { it.copy(isBenchmarkWarmup = true) }
        analysisExecutor.execute {
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
                    filterOption = PngFilterOption.ADAPTIVE_FAST,
                )
                repeat(3) {
                    engine.processFrame(width, height, syntheticRgba).onSuccess { output ->
                        val targetBitmap = acquireBitmap(output.width, output.height)
                        targetBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(output.rgbaBytes))
                        _uiState.update { state ->
                            state.copy(
                                previewBitmap = targetBitmap,
                                frameNumber = state.frameNumber + 1L,
                                processingTimeMs = output.processingTimeMs,
                                frameWidth = output.width,
                                frameHeight = output.height,
                            )
                        }
                    }
                }
            }
            engine.captureSnapshotPng()
        }
    }

    fun analyzeImage(image: ImageProxy) {
        if (!processingFrame.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val currentState = _uiState.value
            val mirror = currentState.lensFacing == CameraSelector.LENS_FACING_FRONT
            val extracted = FrameConverter.extractRgba(image, mirror, scratchHolder)
            image.close()

            val glitchResult = engine.processFrame(
                width = extracted.width,
                height = extracted.height,
                rgba = extracted.rgbaBytes,
            )
            glitchResult.onSuccess { output ->
                val targetBitmap = acquireBitmap(output.width, output.height)
                targetBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(output.rgbaBytes))
                synchronized(recorderLock) {
                    val recorder = activeRecorder
                    if (recorder != null && recorder.width == output.width && recorder.height == output.height) {
                        runCatching { recorder.submitFrame(targetBitmap) }
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        previewBitmap = targetBitmap,
                        frameNumber = state.frameNumber + 1L,
                        processingTimeMs = output.processingTimeMs,
                        frameWidth = output.width,
                        frameHeight = output.height,
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(statusMessage = err.message ?: "Glitch failed")
                }
            }
        } catch (t: Throwable) {
            runCatching { image.close() }
            _uiState.update { state ->
                state.copy(statusMessage = t.message ?: "Frame error")
            }
        } finally {
            processingFrame.set(false)
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
        analysisExecutor.execute {
            val snapResult = engine.captureSnapshotPng()
            snapResult.onSuccess { pngBytes ->
                viewModelScope.launch(Dispatchers.IO) {
                    val saveResult = MediaStoreHelper.savePngSnapshot(appContext, pngBytes)
                    _uiState.update { state ->
                        state.copy(
                            statusMessage = saveResult.fold(
                                onSuccess = { "Saved PNG (${pngBytes.size / 1024} KB) to Pictures/GlitchCamera" },
                                onFailure = { "PNG save failed: ${it.message}" },
                            ),
                        )
                    }
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
                viewModelScope.launch(Dispatchers.IO) {
                    val file = runCatching { existing.finish() }.getOrNull()
                    if (file != null && file.exists()) {
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
                        _uiState.update { it.copy(statusMessage = "Recording stopped") }
                    }
                }
            } else {
                val state = _uiState.value
                val recWidth = if (state.frameWidth > 0) state.frameWidth else state.resolution.height
                val recHeight = if (state.frameHeight > 0) state.frameHeight else state.resolution.width
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
        analysisExecutor.execute {
            engine.updateConfig(
                recipeYaml = yaml,
                seed = seed,
                seedMode = seedMode,
                filterOption = filterOption,
            )
        }
    }

    private fun acquireBitmap(width: Int, height: Int): Bitmap {
        bitmapPoolIndex = (bitmapPoolIndex + 1) % bitmapPool.size
        val existing = bitmapPool[bitmapPoolIndex]
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmapPool[bitmapPoolIndex] = created
        return created
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(recorderLock) {
            activeRecorder?.close()
            activeRecorder = null
        }
        analysisExecutor.execute {
            engine.close()
        }
        analysisExecutor.shutdown()
    }
}
