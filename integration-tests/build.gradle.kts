import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// End-to-end tests: generate bindings with the CLI, compile them, and run them on Wasmtime (needs
// wasm-tools and a built rust-bridge) and on Chicory, including Wasmtime/Chicory differential tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(project(":cli"))
    testImplementation(project(":generator"))
    testImplementation(project(":runtime-wasmtime"))
    testImplementation(project(":runtime-chicory"))
    testImplementation(libs.clikt)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("compiler-embeddable"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjdk-release=21", "-Xcontext-parameters")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The component fixtures live in the top-level fixtures/ directory (shared with tooling, the
// Gradle plugin tests and the decomposer); expose them as /fixtures/... test resources.
sourceSets.test {
    resources {
        setSrcDirs(listOf(rootDir))
        include("fixtures/**")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    // Tests refer to examples/, rust-bridge/target/ and runtime-chicory test data relative to the repository root.
    workingDir = rootDir
}
