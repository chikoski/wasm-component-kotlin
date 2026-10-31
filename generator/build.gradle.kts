import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates Kotlin host bindings (KotlinPoet) from the WIT model. The generated code targets
// runtime-api, plus runtime-wasmtime unless Wasmtime sessions are disabled.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    api(project(":ast"))
    api(libs.kotlinpoet)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
