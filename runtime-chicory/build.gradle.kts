import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-JVM component runtime: replays a wasm2kt-decompose plan on Chicory. JVM 11 for Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.animalsniffer)
    `maven-publish`
}

dependencies {
    api(project(":runtime-api"))
    api(libs.chicory.runtime)
    api(libs.chicory.wasm)
    // plan.json
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(project(":test-support"))
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

// Android's API level, not only the JDK 11 API: Android lacks some Java 9-11 APIs (#80). The minSdk
// matches samples/android; calls into the class path (Kotlin, Chicory) are checked by their own use.
dependencies {
    signature("${libs.android.api.signature.get()}@signature")
}
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}
