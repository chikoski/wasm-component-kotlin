plugins {
    kotlin("jvm")
    id("io.github.wasm2kt")
    application
}

wasm2kt {
    components {
        register("roundtrip") {
            source.set(file("roundtrip.wasm"))
            packageName.set("com.example.chicory")
        }
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(".")
        kotlin.exclude("*.kts")
    }
}

application {
    mainClass.set("com.example.chicory.MainKt")
}
