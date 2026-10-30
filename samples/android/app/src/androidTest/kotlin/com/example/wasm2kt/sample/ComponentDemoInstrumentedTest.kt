package com.example.wasm2kt.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Same as ComponentDemoTest, on ART: `./gradlew -p samples/android :app:connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class ComponentDemoInstrumentedTest {
    @Test
    fun componentsRunOnDevice() {
        assertEquals(
            listOf(
            "math-api.scale(7) = 42",
            "describe-code(\"ERR\", 404) = ERR#404",
            "transform-summary(add-count 5).count = 15",
            "check-even(84) = 42",
            "nested-option(none) is none = true",
            "echo-u64(max) = 18446744073709551615",
            "counter merge(11, 31) = 42",
        ),
            ComponentDemo.run(),
        )
    }
}
