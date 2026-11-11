package com.example.wasm2kt.glitchcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    private val viewModel: GlitchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_BENCHMARK_WARMUP, false) == true) {
            viewModel.runBenchmarkWarmup()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GlitchCameraScreen(viewModel = viewModel)
            }
        }
    }

    companion object {
        const val EXTRA_BENCHMARK_WARMUP = "com.example.wasm2kt.glitchcamera.BENCHMARK_WARMUP"
    }
}
