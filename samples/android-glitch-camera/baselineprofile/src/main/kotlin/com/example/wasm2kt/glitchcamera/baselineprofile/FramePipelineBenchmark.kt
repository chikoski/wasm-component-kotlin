package com.example.wasm2kt.glitchcamera.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.TraceSectionMetric.Mode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Measures the frame pipeline (conversion, glitch engine, presentation) on synthetic frames, so it
 * runs on an emulator without a camera. The app runs [FRAME_COUNT] frames as fast as the engine
 * takes them (`GlitchViewModel.runSyntheticFrames`) and the metrics read the trace sections of
 * `FrameTrace` in the app, whose names are repeated here.
 *
 * - `glitch.syntheticRun` (First): wall time of the whole run in the engine stage; the engine's
 *   throughput is FRAME_COUNT / this.
 * - `glitch.convert` (Average): per-frame time in the conversion stage, which overlaps the engine
 *   stage.
 * - `glitch.analyze` and its parts (Average): per-frame time in the engine stage.
 * - `glitch.present` and its parts (Average): per-frame time on the presentation thread.
 * - `glitch.present` (Count): frames actually shown; fewer than FRAME_COUNT when the presentation
 *   thread is the slower stage (it drops the frames it has not taken yet).
 *
 * The engine stage's sections are async trace sections (its calls suspend), which Android records
 * from API 29: run this on a device or emulator with API 29 or later.
 *
 * Run with:
 * ./gradlew -p samples/android-glitch-camera :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.wasm2kt.glitchcamera.baselineprofile.FramePipelineBenchmark
 */
@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(Parameterized::class)
class FramePipelineBenchmark(private val resolution: String) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun syntheticFrames() {
        val targetPackage = InstrumentationRegistry.getArguments().getString("targetAppId") ?: TARGET_PACKAGE
        rule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                TraceSectionMetric("glitch.syntheticRun", Mode.First),
                TraceSectionMetric("glitch.analyze", Mode.Average),
                TraceSectionMetric("glitch.convert", Mode.Average),
                TraceSectionMetric("glitch.processFrame", Mode.Average),
                TraceSectionMetric("glitch.present", Mode.Average),
                TraceSectionMetric("glitch.copyToBitmap", Mode.Average),
                TraceSectionMetric("glitch.draw", Mode.Average),
                TraceSectionMetric("glitch.present", Mode.Count, label = "glitch.presentedFrames"),
            ),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                pressHome()
            },
        ) {
            startActivityAndWait { intent ->
                intent.putExtra("com.example.wasm2kt.glitchcamera.SYNTHETIC_FRAMES", FRAME_COUNT)
                intent.putExtra("com.example.wasm2kt.glitchcamera.SYNTHETIC_RESOLUTION", resolution)
            }
            // GlitchViewModel.SYNTHETIC_RUN_DONE, shown once the last frame was presented.
            val done = device.wait(Until.hasObject(By.text("Synthetic frames done")), 180_000)
            assertTrue("The synthetic run did not finish", done == true)
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.wasm2kt.glitchcamera"
        private const val FRAME_COUNT = 120

        /** `CameraResolutionOption` names. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun resolutions(): List<String> = listOf("SD_360P", "QHD_540P", "HD_720P")
    }
}
