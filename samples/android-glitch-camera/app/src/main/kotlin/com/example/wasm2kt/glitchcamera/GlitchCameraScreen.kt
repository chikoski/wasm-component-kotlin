package com.example.wasm2kt.glitchcamera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamKind
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun GlitchCameraScreen(viewModel: GlitchViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        DisposableEffect(lifecycleOwner, state.lensFacing, state.resolution) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            val listener = Runnable {
                val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(state.resolution.width, state.resolution.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(viewModel.analysisExecutor) { imageProxy ->
                    viewModel.analyzeImage(imageProxy)
                }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(state.lensFacing)
                    .build()

                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
                }
            }
            providerFuture.addListener(listener, mainExecutor)

            onDispose {
                runCatching {
                    providerFuture.get().unbindAll()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F12)),
    ) {
        TopCameraBar(
            state = state,
            onToggleLens = viewModel::toggleCameraLens,
            onSelectResolution = viewModel::selectResolution,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (!hasCameraPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera permission is required for live glitching.",
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                }
            } else {
                val bmp = state.previewBitmap
                // Observe frameNumber so Canvas redraws when the pooled Bitmap pixels are updated.
                val currentFrame = state.frameNumber
                if (bmp != null && currentFrame >= 0L) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imageBitmap = bmp.asImageBitmap()
                        val scale = min(
                            size.width / imageBitmap.width.toFloat(),
                            size.height / imageBitmap.height.toFloat(),
                        )
                        val dstW = (imageBitmap.width * scale).roundToInt()
                        val dstH = (imageBitmap.height * scale).roundToInt()
                        val offsetX = ((size.width - dstW) / 2f).roundToInt()
                        val offsetY = ((size.height - dstH) / 2f).roundToInt()
                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset(offsetX, offsetY),
                            dstSize = IntSize(dstW, dstH),
                        )
                    }
                } else {
                    Text(
                        text = "Waiting for camera frame...",
                        color = Color.LightGray,
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    color = Color(0xCC1C1E26),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        BottomControlPanel(
            state = state,
            onSelectControlMode = viewModel::selectControlMode,
            onSelectPreset = viewModel::selectPreset,
            onSelectFilter = viewModel::selectSingleFilter,
            onUpdateParam = viewModel::updateFilterParam,
            onSelectSeedMode = viewModel::selectSeedMode,
            onRandomizeSeed = viewModel::randomizeSeed,
            onSelectPngFilter = viewModel::selectPngFilterOption,
            onCapturePng = { viewModel.captureStillPng(context) },
            onToggleRecording = { viewModel.toggleVideoRecording(context) },
        )
    }
}

@Composable
private fun TopCameraBar(
    state: GlitchUiState,
    onToggleLens: () -> Unit,
    onSelectResolution: (CameraResolutionOption) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF16181E))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "PNG-GLITCH LIVE",
                color = Color(0xFF7CE38B),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            val perfText = if (state.frameWidth > 0) {
                String.format(
                    Locale.US,
                    "%dx%d  %.1f ms/frame",
                    state.frameWidth,
                    state.frameHeight,
                    state.processingTimeMs,
                )
            } else {
                "Initializing..."
            }
            Text(
                text = perfText,
                color = Color.LightGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraResolutionOption.entries.forEach { option ->
                FilterChip(
                    selected = state.resolution == option,
                    onClick = { onSelectResolution(option) },
                    label = { Text(option.label, fontSize = 11.sp) },
                )
            }
            OutlinedButton(onClick = onToggleLens) {
                val lensLabel = if (state.lensFacing == CameraSelector.LENS_FACING_BACK) "Front" else "Back"
                Text(lensLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BottomControlPanel(
    state: GlitchUiState,
    onSelectControlMode: (ControlMode) -> Unit,
    onSelectPreset: (RecipePreset) -> Unit,
    onSelectFilter: (com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.FilterInfo) -> Unit,
    onUpdateParam: (String, String) -> Unit,
    onSelectSeedMode: (SeedMode) -> Unit,
    onRandomizeSeed: () -> Unit,
    onSelectPngFilter: (PngFilterOption) -> Unit,
    onCapturePng: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .background(Color(0xFF16181E))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.controlMode == mode,
                        onClick = { onSelectControlMode(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCapturePng) {
                    Text("Save PNG", fontSize = 12.sp)
                }
                Button(
                    onClick = onToggleRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRecording) Color(0xFFD9383A) else MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Text(if (state.isRecording) "Stop MP4" else "Rec MP4", fontSize = 12.sp)
                }
            }
        }

        if (state.controlMode == ControlMode.PRESETS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecipePresets.curated.forEach { preset ->
                    FilterChip(
                        selected = state.selectedPreset.id == preset.id,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.title) },
                    )
                }
            }
            Text(
                text = state.selectedPreset.subtitle,
                color = Color.LightGray,
                fontSize = 12.sp,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.availableFilters.forEach { filter ->
                    FilterChip(
                        selected = state.selectedFilter?.name == filter.name,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.name, fontSize = 12.sp) },
                    )
                }
            }
            state.selectedFilter?.let { filter ->
                Text(
                    text = filter.doc,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                )
                filter.params.forEach { param ->
                    ParameterControl(
                        param = param,
                        currentValue = state.filterParams[param.name]
                            ?: RecipePresets.defaultParamValue(filter, param),
                        onValueChange = { newVal -> onUpdateParam(param.name, newVal) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PNG Filter:", color = Color.LightGray, fontSize = 11.sp)
            PngFilterOption.entries.forEach { option ->
                FilterChip(
                    selected = state.pngFilterOption == option,
                    onClick = { onSelectPngFilter(option) },
                    label = { Text(option.label, fontSize = 11.sp) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Seed:", color = Color.LightGray, fontSize = 11.sp)
            FilterChip(
                selected = state.seedMode == SeedMode.PER_FRAME,
                onClick = { onSelectSeedMode(SeedMode.PER_FRAME) },
                label = { Text("PerFrame", fontSize = 11.sp) },
            )
            FilterChip(
                selected = state.seedMode == SeedMode.FIXED,
                onClick = { onSelectSeedMode(SeedMode.FIXED) },
                label = { Text("Fixed (${state.seed})", fontSize = 11.sp) },
            )
            OutlinedButton(onClick = onRandomizeSeed) {
                Text("New Seed", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ParameterControl(
    param: ParamInfo,
    currentValue: String,
    onValueChange: (String) -> Unit,
) {
    when (param.kind) {
        ParamKind.FLOAT -> {
            val range = RecipePresets.sliderRangeFor(param)
            val numeric = currentValue.toFloatOrNull() ?: range.start
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${param.name}: ${RecipePresets.formatFloat(numeric.toDouble())}",
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Slider(
                    value = numeric.coerceIn(range.start, range.endInclusive),
                    onValueChange = { onValueChange(RecipePresets.formatFloat(it.toDouble())) },
                    valueRange = range,
                )
            }
        }
        ParamKind.INT -> {
            val range = RecipePresets.sliderRangeFor(param)
            val numeric = currentValue.toFloatOrNull() ?: range.start
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${param.name}: ${numeric.roundToInt()}",
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Slider(
                    value = numeric.coerceIn(range.start, range.endInclusive),
                    onValueChange = { onValueChange(it.roundToInt().toString()) },
                    valueRange = range,
                )
            }
        }
        ParamKind.ENUM, ParamKind.STRATEGY -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = param.name,
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    param.choices.forEach { choice ->
                        FilterChip(
                            selected = currentValue == choice,
                            onClick = { onValueChange(choice) },
                            label = { Text(choice, fontSize = 11.sp) },
                        )
                    }
                }
            }
        }
    }
}
