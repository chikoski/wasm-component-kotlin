package com.example.wasm2kt.glitchcamera.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetPackage = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "com.example.wasm2kt.glitchcamera"

        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true,
        ) {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            pressHome()
            startActivityAndWait { intent ->
                intent.putExtra("com.example.wasm2kt.glitchcamera.BENCHMARK_WARMUP", true)
            }
            device.wait(Until.hasObject(By.textContains("ms/frame")), 10_000)
            device.waitForIdle()
        }
    }
}
