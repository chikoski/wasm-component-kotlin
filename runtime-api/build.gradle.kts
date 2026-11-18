import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared runtime contract for generated bindings. Kept at JVM 17 so it can ship to Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.animalsniffer)
    `maven-publish`
}

dependencies {
    // The async ABI's host boundary is suspending (ADR-0019): suspend calls, Flow streams.
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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

// Android's API level, not only the JDK 17 API: Android lacks some Java 9-17 APIs (#80). The minSdk
// matches samples/android; calls into the class path (Kotlin, Chicory) are checked by their own use.
dependencies {
    signature("${libs.android.api.signature.get()}@signature")
}
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}
