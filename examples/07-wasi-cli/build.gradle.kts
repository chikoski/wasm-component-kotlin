plugins {
    kotlin("jvm")
    id("io.github.wasm2kt")
    application
}

wasm2kt {
    addWasiDependency.set(true)
    components {
        register("wasi-cli") {
            source.set(file("wasi-cli.wasm"))
            packageName.set("com.example.wasicli")
        }
    }
}

sourceSets.main {
    kotlin.srcDir(".")
    kotlin.exclude("*.kts")
}

application {
    mainClass.set("com.example.wasicli.MainKt")
}
