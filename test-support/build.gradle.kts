import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test-only helpers (not published): drive backends with JSON-written values, typed by a component's
// plan.json (ADR-0015). Kept at JVM 17 so runtime-chicory's tests can use it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":runtime-api"))
    api(libs.kotlinx.serialization.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
