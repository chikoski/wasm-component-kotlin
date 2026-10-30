import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM component runtime: replays a wasm2kt-decompose plan on Chicory. JVM 11 for Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":runtime-api"))
    api(libs.chicory.runtime)
    api(libs.chicory.wasm)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
}
