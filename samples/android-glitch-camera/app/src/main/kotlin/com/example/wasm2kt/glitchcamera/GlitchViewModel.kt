package com.example.wasm2kt.glitchcamera

import android.content.Context
import android.graphics.Bitmap
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
    VGA_640X480("640x480", 640, 480),
    HD_1280X720("1280x720", 1280, 720),
}

enum class ControlMode(val label: String) {
    PRESETS("Presets"),
    SINGLE_FILTER("Single Filter"),
}

data class GlitchUiState(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val resolution: CameraResolutionOption = CameraResolutionOption.VGA_640X480,
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
    val frameNumber: Long = 0L,
    val processingTimeMs: Double = 0.0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val isRecording: Boolean = false,
    val statusMessage: String? = null,
)

class GlitchViewModel : ViewModel() {
    private val engine = GlitchEngine()
    val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glitch-camera-worker").apply { isDaemon = true }
    }
    private val scratchHolder = RgbaBufferHolder()
    private val processingFrame = AtomicBoolean(false)

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
