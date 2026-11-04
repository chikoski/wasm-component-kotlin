import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test-only helpers (not published): drive backends with JSON-written values, typed by a component's
// plan.json (ADR-0015). Kept at JVM 11 so runtime-chicory's tests can use it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":runtime-api"))
    api(libs.kotlinx.serialization.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
