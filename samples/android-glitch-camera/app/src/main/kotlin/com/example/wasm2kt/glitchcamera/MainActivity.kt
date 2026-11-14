package com.example.wasm2kt.glitchcamera

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    private val viewModel: GlitchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val barColor = 0xFF101211.toInt()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barColor),
            navigationBarStyle = SystemBarStyle.dark(barColor),
        )
        super.onCreate(savedInstanceState)
        val syntheticFrames = intent?.getIntExtra(EXTRA_SYNTHETIC_FRAMES, 0) ?: 0
        val warmup = intent?.getBooleanExtra(EXTRA_BENCHMARK_WARMUP, false) == true
        if (warmup || syntheticFrames > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Only on a fresh start: a recreated activity keeps the run its view model already has.
        if (savedInstanceState == null) {
            if (syntheticFrames > 0) {
                val resolution = intent?.getStringExtra(EXTRA_SYNTHETIC_RESOLUTION)
                    ?.let { name -> CameraResolutionOption.entries.firstOrNull { it.name == name } }
                    ?: CameraResolutionOption.QHD_540P
                viewModel.runSyntheticFrames(syntheticFrames, resolution)
            } else if (warmup) {
                viewModel.runBenchmarkWarmup()
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GlitchCameraScreen(viewModel = viewModel)
            }
        }
    }

    companion object {
        const val EXTRA_BENCHMARK_WARMUP = "com.example.wasm2kt.glitchcamera.BENCHMARK_WARMUP"

        /** Number of synthetic frames to run through the pipeline instead of the camera (benchmarks). */
        const val EXTRA_SYNTHETIC_FRAMES = "com.example.wasm2kt.glitchcamera.SYNTHETIC_FRAMES"

        /** [CameraResolutionOption] name for [EXTRA_SYNTHETIC_FRAMES]; 540p when absent. */
        const val EXTRA_SYNTHETIC_RESOLUTION = "com.example.wasm2kt.glitchcamera.SYNTHETIC_RESOLUTION"
    }
}
