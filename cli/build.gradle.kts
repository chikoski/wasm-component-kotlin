import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The `wasm2kt` command-line tool.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":generator"))
    implementation(libs.clikt)
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

application {
    mainClass.set("wasm2kt.cli.MainKt")
    applicationName = "wasm2kt"
}

// Resolve relative paths in `./gradlew run --args=...` against the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
