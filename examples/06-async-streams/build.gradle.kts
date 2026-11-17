plugins {
    kotlin("jvm")
    id("io.github.wasm2kt")
    application
}

wasm2kt {
    components {
        register("async-pipeline") {
            source.set(file("async-pipeline.wasm"))
            packageName.set("com.example.asyncpipeline")
        }
    }
}

sourceSets.main {
    kotlin.srcDir(".")
    kotlin.exclude("*.kts")
}

application {
    mainClass.set("com.example.asyncpipeline.MainKt")
}
