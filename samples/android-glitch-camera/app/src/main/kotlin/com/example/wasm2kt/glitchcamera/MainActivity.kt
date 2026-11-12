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
        if (intent?.getBooleanExtra(EXTRA_BENCHMARK_WARMUP, false) == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
