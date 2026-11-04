import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared runtime contract for generated bindings. Kept at JVM 11 so it can ship to Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
