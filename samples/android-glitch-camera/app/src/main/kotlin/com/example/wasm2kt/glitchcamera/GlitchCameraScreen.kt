package com.example.wasm2kt.glitchcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.FilterInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamInfo
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.glitch.ParamKind
import com.example.wasm2kt.glitchcamera.pngglitch.exports.chikoski.png_glitch.live.SeedMode
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val ColorBgPrimary = Color(0xFF101211)
private val ColorCameraSurface = Color(0xFF1B211E)
private val ColorOverlayPill = Color(0xFF171D17)
private val ColorAccentLime = Color(0xFFE1EDB0)
private val ColorOnAccentDark = Color(0xFF20271A)
private val ColorStudioSurface = Color(0xFF171A18)
private val ColorStatusBadgeBg = Color(0xFF2C3527)
private val ColorSegmentedBg = Color(0xFF262B25)
private val ColorDivider = Color(0xFF30372F)
private val ColorTrackInactive = Color(0xFF3C433B)
private val ColorCaptureSecondary = Color(0xFF252925)
private val ColorRecordAccent = Color(0xFFFF826F)
private val ColorTextPrimary = Color(0xFFF4F5EF)
private val ColorTextHeader = Color(0xFFF1F3EC)
private val ColorTextSecondary = Color(0xFFD8DED3)
private val ColorTextPillUnselected = Color(0xFFD9E0D2)
private val ColorTextResolutionLabel = Color(0xFFC6CFBD)
private val ColorTextLensUnselected = Color(0xFFB4BDB3)
private val ColorTextTabUnselected = Color(0xFFADB6A7)
private val ColorTextCardUnselected = Color(0xFFABB4A7)
private val ColorTextReset = Color(0xFFBCC6B5)
private val ColorTextNote = Color(0xFF9BA594)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlitchCameraScreen(viewModel: GlitchViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    // Not delegated: reading `previewFrame.value` here would recompose the whole screen on every
    // camera frame. Only the FPS badge and the frame statistics read it.
    val previewFrame = viewModel.previewFrame.collectAsState()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

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
        viewModel.ensureBaseAssetsLoaded(context)
    }

    LaunchedEffect(state.isBenchmarkWarmup) {
        if (!hasCameraPermission && !state.isBenchmarkWarmup) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission && !state.isBenchmarkWarmup) {
        DisposableEffect(lifecycleOwner, state.lensFacing, state.resolution) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            val listener = Runnable {
                val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@Runnable
                // The processing sizes are 16:9. Without an aspect ratio strategy CameraX prefers
                // 4:3 for ImageAnalysis, and may deliver frames much larger than needed (such as
                // 1280x960 for 540p), which costs time in CameraX's YUV to RGBA conversion and in
                // ours.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(state.resolution.longSide, state.resolution.shortSide),
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
                    boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
                }
            }
            providerFuture.addListener(listener, mainExecutor)

            onDispose {
                boundCamera = null
                runCatching {
                    providerFuture.get().unbindAll()
                }
            }
        }
    }

    LaunchedEffect(boundCamera, state.zoomOption) {
        val cam = boundCamera ?: return@LaunchedEffect
        val zoomState = cam.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 0.5f
        val maxZoom = zoomState?.maxZoomRatio ?: 4.0f
        val targetZoom = state.zoomOption.ratio.coerceIn(minZoom, maxZoom)
        runCatching {
            cam.cameraControl.setZoomRatio(targetZoom)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBgPrimary)
            .systemBarsPadding(),
    ) {
        val isFoldableLayout = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            PixelfrayAppBar(
                onOpenSettings = { showSettingsSheet = true },
            )

            if (isFoldableLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LiveCameraSection(
                        state = state,
                        previewFrame = previewFrame,
                        previewRenderer = viewModel.previewRenderer,
                        isFoldable = true,
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onSelectResolution = viewModel::selectResolution,
                        onSelectZoom = viewModel::selectZoom,
                        onToggleRecording = { viewModel.toggleVideoRecording(context) },
                        onCapturePng = { viewModel.captureStillPng(context) },
                        onToggleLens = viewModel::toggleCameraLens,
                        modifier = Modifier
                            .weight(580f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp)),
                    )

                    FilterStudioSection(
                        state = state,
                        isFoldable = true,
                        onSelectControlMode = viewModel::selectControlMode,
                        onSelectPreset = viewModel::selectPreset,
                        onSelectFilter = viewModel::selectSingleFilter,
                        onUpdateParam = viewModel::updateFilterParam,
                        onResetParams = viewModel::resetFilterParams,
                        modifier = Modifier
                            .weight(372f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp)),
                    )
                }
            } else {
                LiveCameraSection(
                    state = state,
                    previewFrame = previewFrame,
                    previewRenderer = viewModel.previewRenderer,
                    isFoldable = false,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onSelectResolution = viewModel::selectResolution,
                    onSelectZoom = viewModel::selectZoom,
                    onToggleRecording = { viewModel.toggleVideoRecording(context) },
                    onCapturePng = { viewModel.captureStillPng(context) },
                    onToggleLens = viewModel::toggleCameraLens,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.46f),
                )

                FilterStudioSection(
                    state = state,
                    isFoldable = false,
                    onSelectControlMode = viewModel::selectControlMode,
                    onSelectPreset = viewModel::selectPreset,
                    onSelectFilter = viewModel::selectSingleFilter,
                    onUpdateParam = viewModel::updateFilterParam,
                    onResetParams = viewModel::resetFilterParams,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.54f),
                )
            }
        }

        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = ColorStudioSurface,
                contentColor = ColorTextPrimary,
            ) {
                EngineSettingsSheetContent(
                    state = state,
                    previewFrame = previewFrame,
                    onSelectPngFilter = viewModel::selectPngFilterOption,
                    onSelectSeedMode = viewModel::selectSeedMode,
                    onRandomizeSeed = viewModel::randomizeSeed,
                )
            }
        }
    }
}

@Composable
private fun PixelfrayAppBar(
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ColorBgPrimary)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Pixelfray",
            color = ColorAccentLime,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_settings),
                contentDescription = "設定",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun LiveCameraSection(
    state: GlitchUiState,
    previewFrame: State<PreviewFrame>,
    previewRenderer: PreviewRenderer,
    isFoldable: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSelectResolution: (CameraResolutionOption) -> Unit,
    onSelectZoom: (CameraZoomOption) -> Unit,
    onToggleRecording: () -> Unit,
    onCapturePng: () -> Unit,
    onToggleLens: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clipToBounds()
            .background(ColorCameraSurface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .background(ColorCameraSurface),
        ) {
            // Frames are drawn into this surface by PreviewRenderer on the analysis worker
            // thread, so a new frame involves neither recomposition nor a Compose draw pass.
            AndroidExternalSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                previewRenderer.backgroundColor = ColorCameraSurface.toArgb()
                onSurface { surface, width, height ->
                    previewRenderer.attach(surface, width, height)
                    surface.onChanged { newWidth, newHeight -> previewRenderer.resize(this, newWidth, newHeight) }
                    surface.onDestroyed { previewRenderer.detach(this) }
                }
            }

            // Preview contrast gradient overlay (node 2:30 / 2:74)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.35f),
                            0.4f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.35f),
                        ),
                    ),
            )

            // 3x3 rule-of-thirds grid overlay (nodes 2:31..2:34 / 2:75..2:78)
            RuleOfThirdsGrid(modifier = Modifier.fillMaxSize())

            // Resolution selector pill (node 2:38 / 2:82)
            ResolutionSelectorPill(
                selected = state.resolution,
                onSelect = onSelectResolution,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp),
            )

            if (!hasCameraPermission && !state.isBenchmarkWarmup) {
                Surface(
                    color = ColorOverlayPill.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 16.dp)
                        .clickable(onClick = onRequestPermission),
                ) {
                    Text(
                        text = "カメラ権限を許可",
                        color = ColorAccentLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Lens zoom selection pill (node 2:40 / 2:84)
            LensZoomSelectorPill(
                selected = state.zoomOption,
                onSelect = onSelectZoom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 15.dp),
            )

            // FPS badge pill (node 5:9 / 5:18)
            FpsBadgePill(
                previewFrame = previewFrame,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 15.dp, end = 16.dp),
            )

            state.statusMessage?.let { msg ->
                Surface(
                    color = ColorOverlayPill.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 50.dp),
                ) {
                    Text(
                        text = msg,
                        color = ColorTextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        CaptureControlsBar(
            isRecording = state.isRecording,
            isFoldable = isFoldable,
            onToggleRecording = onToggleRecording,
            onCapturePng = onCapturePng,
            onToggleLens = onToggleLens,
        )
    }
}

@Composable
private fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    val gridColor = Color.White.copy(alpha = 0.14f)
    Canvas(modifier = modifier) {
        val strokePx = 1.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(
            color = gridColor,
            start = Offset(w / 3f, 0f),
            end = Offset(w / 3f, h),
            strokeWidth = strokePx,
        )
        drawLine(
            color = gridColor,
            start = Offset(w * 2f / 3f, 0f),
            end = Offset(w * 2f / 3f, h),
            strokeWidth = strokePx,
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, h / 3f),
            end = Offset(w, h / 3f),
            strokeWidth = strokePx,
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, h * 2f / 3f),
            end = Offset(w, h * 2f / 3f),
            strokeWidth = strokePx,
        )
    }
}

@Composable
private fun ResolutionSelectorPill(
    selected: CameraResolutionOption,
    onSelect: (CameraResolutionOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ColorOverlayPill)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "解像度",
            color = ColorTextResolutionLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 8.dp),
        )
        CameraResolutionOption.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) ColorAccentLime else ColorOverlayPill)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (isSelected) ColorOnAccentDark else ColorTextPillUnselected,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LensZoomSelectorPill(
    selected: CameraZoomOption,
    onSelect: (CameraZoomOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(110.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ColorOverlayPill)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraZoomOption.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) ColorAccentLime else Color.Transparent)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isSelected) option.selectedLabel else option.unselectedLabel,
                    color = if (isSelected) ColorOnAccentDark else ColorTextLensUnselected,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun FpsBadgePill(
    previewFrame: State<PreviewFrame>,
    modifier: Modifier = Modifier,
) {
    // Reading the frame here limits per-frame recomposition to this badge.
    val frame = previewFrame.value
    val fpsText = if (frame.displayFps > 0.0) {
        "${frame.displayFps.roundToInt().coerceIn(1, 120)} fps"
    } else {
        "-- fps"
    }
    val perfDesc = if (frame.width > 0) {
        String.format(
            Locale.US,
            "%dx%d %.1f ms/frame in engine (%s)",
            frame.width,
            frame.height,
            frame.processingTimeMs,
            fpsText,
        )
    } else {
        fpsText
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ColorOverlayPill)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = perfDesc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fpsText,
            color = ColorTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CaptureControlsBar(
    isRecording: Boolean,
    isFoldable: Boolean,
    onToggleRecording: () -> Unit,
    onCapturePng: () -> Unit,
    onToggleLens: () -> Unit,
) {
    val barHeight = if (isFoldable) 108.dp else 94.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(ColorBgPrimary),
        horizontalArrangement = Arrangement.spacedBy(55.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Record video button (node 2:46 / 2:90)
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .then(
                    if (isRecording) {
                        Modifier.border(2.dp, ColorRecordAccent, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onToggleRecording),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_video_record),
                contentDescription = if (isRecording) "録画を停止" else "録画を開始",
                modifier = Modifier.size(46.dp),
            )
        }

        // Photo shutter button (nodes 2:51 + 2:52 / 2:95 + 2:96)
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .clickable(onClick = onCapturePng),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_shutter_outer),
                contentDescription = "写真を撮影",
                modifier = Modifier.size(62.dp),
            )
            Image(
                painter = painterResource(id = R.drawable.ic_shutter_inner),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
        }

        // Camera flip button (node 2:53 / 2:97)
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(ColorCaptureSecondary)
                .clickable(onClick = onToggleLens),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_flip_camera),
                contentDescription = "カメラ切替",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun FilterStudioSection(
    state: GlitchUiState,
    isFoldable: Boolean,
    onSelectControlMode: (ControlMode) -> Unit,
    onSelectPreset: (RecipePreset) -> Unit,
    onSelectFilter: (FilterInfo) -> Unit,
    onUpdateParam: (String, String) -> Unit,
    onResetParams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(ColorStudioSurface)
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = if (isFoldable) 20.dp else 12.dp,
                bottom = 12.dp,
            ),
    ) {
        // Header row: "フィルター" + "適用中" badge (nodes 2:103, 2:104 / 2:138, 2:139)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "フィルター",
                color = ColorTextHeader,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            Row(
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorStatusBadgeBg)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_status_dot),
                    contentDescription = null,
                    modifier = Modifier.size(5.dp),
                )
                Text(
                    text = "適用中",
                    color = ColorAccentLime,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Segmented tab bar: "プリセット" / "シングル" (node 2:107 / 2:142)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ColorSegmentedBg)
                .padding(4.dp),
        ) {
            ControlMode.entries.forEach { mode ->
                val isSelected = state.controlMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) ColorAccentLime else Color.Transparent)
                        .clickable { onSelectControlMode(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = mode.label,
                        color = if (isSelected) ColorOnAccentDark else ColorTextTabUnselected,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Horizontal filter thumbnail cards (nodes 2:111..2:121 / 2:146..2:156)
        val cardWidth = if (isFoldable) 74.dp else 84.dp
        val cardHeight = if (isFoldable) 84.dp else 64.dp
        val labelFontSize = if (isFoldable) 10.sp else 11.sp

        if (state.controlMode == ControlMode.PRESETS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecipePresets.curated.forEach { preset ->
                    val isSelected = state.selectedPreset.id == preset.id
                    FilterThumbnailCard(
                        title = preset.title,
                        thumbnail = state.presetThumbnails[preset.id] ?: state.fallbackPreviewBitmap,
                        isSelected = isSelected,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight,
                        labelFontSize = labelFontSize,
                        onClick = { onSelectPreset(preset) },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.availableFilters.forEach { filter ->
                    val isSelected = state.selectedFilter?.name == filter.name
                    FilterThumbnailCard(
                        title = filter.name,
                        thumbnail = state.filterThumbnails[filter.name] ?: state.fallbackPreviewBitmap,
                        isSelected = isSelected,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight,
                        labelFontSize = labelFontSize,
                        onClick = { onSelectFilter(filter) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section divider (node 2:122 / 2:157)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorDivider),
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Detail & parameter controls area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.controlMode == ControlMode.PRESETS) {
                Text(
                    text = state.selectedPreset.title,
                    color = ColorTextHeader,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.selectedPreset.subtitle,
                    color = ColorTextNote,
                    fontSize = 12.sp,
                )
            } else {
                val filter = state.selectedFilter
                if (filter != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = filter.name,
                            color = ColorTextHeader,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "リセット",
                            color = ColorTextReset,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onResetParams)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = filter.doc,
                        color = ColorTextNote,
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        filter.params.forEach { param ->
                            PixelfrayParameterControl(
                                param = param,
                                currentValue = state.filterParams[param.name]
                                    ?: RecipePresets.defaultParamValue(filter, param),
                                onValueChange = { newVal -> onUpdateParam(param.name, newVal) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Footer note (node 2:137 / 2:181)
        Text(
            text = if (state.controlMode == ControlMode.PRESETS) {
                "フィルターは撮影した写真・動画に適用されます"
            } else {
                "変更はプレビューにリアルタイムで反映されます"
            },
            color = ColorTextNote,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun FilterThumbnailCard(
    title: String,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(ColorCameraSurface)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, ColorAccentLime, RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (thumbnail != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                ) {
                    val img = thumbnail.asImageBitmap()
                    val scale = max(
                        size.width / img.width.toFloat(),
                        size.height / img.height.toFloat(),
                    )
                    val dstW = (img.width * scale).roundToInt()
                    val dstH = (img.height * scale).roundToInt()
                    val offsetX = ((size.width - dstW) / 2f).roundToInt()
                    val offsetY = ((size.height - dstH) / 2f).roundToInt()
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(offsetX, offsetY),
                        dstSize = IntSize(dstW, dstH),
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(ColorAccentLime),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_selected_check),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = if (isSelected) ColorAccentLime else ColorTextCardUnselected,
            fontSize = labelFontSize,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PixelfrayParameterControl(
    param: ParamInfo,
    currentValue: String,
    onValueChange: (String) -> Unit,
) {
    when (param.kind) {
        ParamKind.FLOAT -> {
            val range = RecipePresets.sliderRangeFor(param)
            val numeric = (currentValue.toFloatOrNull() ?: range.start).coerceIn(range.start, range.endInclusive)
            val displayValue = if (range.start == 0f && range.endInclusive == 1f) {
                "${(numeric * 100f).roundToInt()}%"
            } else {
                RecipePresets.formatFloat(numeric.toDouble())
            }
            PixelfraySliderRow(
                label = param.name,
                valueText = displayValue,
                value = numeric,
                valueRange = range,
                onValueChange = { onValueChange(RecipePresets.formatFloat(it.toDouble())) },
            )
        }
        ParamKind.INT -> {
            val range = RecipePresets.sliderRangeFor(param)
            val numeric = (currentValue.toFloatOrNull() ?: range.start).coerceIn(range.start, range.endInclusive)
            PixelfraySliderRow(
                label = param.name,
                valueText = numeric.roundToInt().toString(),
                value = numeric,
                valueRange = range,
                onValueChange = { onValueChange(it.roundToInt().toString()) },
            )
        }
        ParamKind.ENUM, ParamKind.STRATEGY -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = param.name,
                        color = ColorTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = currentValue,
                        color = ColorAccentLime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    param.choices.forEach { choice ->
                        val isSelected = currentValue == choice
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ColorAccentLime else ColorSegmentedBg)
                                .clickable { onValueChange(choice) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = choice,
                                color = if (isSelected) ColorOnAccentDark else ColorTextTabUnselected,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PixelfraySliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = ColorTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = valueText,
                color = ColorAccentLime,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onSizeChanged { size ->
                    trackWidthPx = size.width.toFloat().coerceAtLeast(1f)
                }
                .pointerInput(valueRange) {
                    detectTapGestures { offset ->
                        val f = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + f * span)
                    }
                }
                .pointerInput(valueRange) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val f = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + f * span)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Inactive track (4dp height, #3C433B)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ColorTrackInactive),
            )

            // Active track (4dp height, #E1EDB0)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ColorAccentLime),
            )

            // Slider thumb (16dp, R.drawable.ic_slider_thumb)
            val thumbOffsetDp = with(density) {
                val thumbRadiusPx = 8.dp.toPx()
                val rawPx = (fraction * trackWidthPx - thumbRadiusPx)
                    .coerceIn(0f, (trackWidthPx - thumbRadiusPx * 2f).coerceAtLeast(0f))
                rawPx.toDp()
            }
            Image(
                painter = painterResource(id = R.drawable.ic_slider_thumb),
                contentDescription = "Adjust $label",
                modifier = Modifier
                    .offset(x = thumbOffsetDp)
                    .size(16.dp),
            )
        }
    }
}

@Composable
private fun EngineSettingsSheetContent(
    state: GlitchUiState,
    previewFrame: State<PreviewFrame>,
    onSelectPngFilter: (PngFilterOption) -> Unit,
    onSelectSeedMode: (SeedMode) -> Unit,
    onRandomizeSeed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "エンジン設定",
            color = ColorTextHeader,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "PNG Filter Strategy",
                color = ColorTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PngFilterOption.entries.forEach { option ->
                    val isSelected = state.pngFilterOption == option
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) ColorAccentLime else ColorSegmentedBg)
                            .clickable { onSelectPngFilter(option) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            color = if (isSelected) ColorOnAccentDark else ColorTextTabUnselected,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Seed Mode",
                color = ColorTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isPerFrame = state.seedMode == SeedMode.PER_FRAME
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isPerFrame) ColorAccentLime else ColorSegmentedBg)
                        .clickable { onSelectSeedMode(SeedMode.PER_FRAME) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "PerFrame",
                        color = if (isPerFrame) ColorOnAccentDark else ColorTextTabUnselected,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                val isFixed = state.seedMode == SeedMode.FIXED
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFixed) ColorAccentLime else ColorSegmentedBg)
                        .clickable { onSelectSeedMode(SeedMode.FIXED) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Fixed (${state.seed})",
                        color = if (isFixed) ColorOnAccentDark else ColorTextTabUnselected,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, ColorAccentLime, RoundedCornerShape(10.dp))
                        .clickable(onClick = onRandomizeSeed)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "New Seed",
                        color = ColorAccentLime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        FrameStatsText(previewFrame = previewFrame)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** Frame statistics; reads [previewFrame] itself so that only this text recomposes per frame. */
@Composable
private fun FrameStatsText(previewFrame: State<PreviewFrame>) {
    val frame = previewFrame.value
    if (frame.width > 0) {
        Text(
            text = String.format(
                Locale.US,
                "Frame: %dx%d  ·  %.1f ms/frame in engine  ·  %.1f fps",
                frame.width,
                frame.height,
                frame.processingTimeMs,
                frame.displayFps,
            ),
            color = ColorTextNote,
            fontSize = 11.sp,
        )
    }
}
