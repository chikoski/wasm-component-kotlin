package com.example.wasm2kt.sample

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the generated bindings and AOT-compiled core modules on the host JVM. */
class ComponentDemoTest {
    @Test
    fun componentsRun() {
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
