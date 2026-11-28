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
    testImplementation(project(":runtime-aot"))
    testImplementation(project(":runtime-wasi"))
    testImplementation(project(":test-support"))
    testImplementation(project(":tooling"))
    testImplementation(libs.clikt)
    testImplementation(libs.kotlinx.coroutines.core)
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
    useJUnitPlatform { excludeTags("typed-bridge") }
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    // Tests refer to examples/, rust-bridge/target/ and runtime-chicory test data relative to the repository root.
    workingDir = rootDir
}

// PR-mandatory suite (ADR-0024): AOT tests, Wasmtime reference tests, and Chicory async conformance tests.
tasks.register<Test>("prTest") {
    description = "Runs the PR-mandatory integration tests (AOT, Wasmtime, and Chicory async conformance)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { excludeTags("typed-bridge", "chicory-sync") }
    exclude("wasm2kt/ChicoryDifferentialTest.class", "wasm2kt/ChicoryDifferentialJniTest.class")
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
}

// Secondary Chicory sync suites: triggered conditionally on relevant path changes and weekly schedule (ADR-0024).
tasks.register<Test>("chicorySyncTest") {
    description = "Runs the Chicory synchronous differential and conformance suites."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { excludeTags("chicory-async") }
    include(
        "wasm2kt/ChicoryDifferentialTest.class",
        "wasm2kt/ChicoryDifferentialJniTest.class",
        "wasm2kt/conformance/ConformanceTest.class",
    )
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
}

// The tests on a typed Wasmtime bridge (ADR-0016) build one with cargo and load it instead of the
// generic bridge, so they run in their own JVM.
val typedBridgeTest by tasks.registering(Test::class) {
    description = "Runs the integration tests on a typed Wasmtime bridge built with cargo."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    // The tests copy the typed bridge here, so it is on this task's class path only (#65).
    val bridgeResources = layout.buildDirectory.dir("typed-bridge-resources")
    classpath = sourceSets.test.get().runtimeClasspath + files(bridgeResources)
    systemProperty("wasm2kt.test.bridgeResources", bridgeResources.get().asFile.absolutePath)
    // The JVM skips class path directories that do not exist when it starts.
    doFirst { bridgeResources.get().asFile.mkdirs() }
    useJUnitPlatform { includeTags("typed-bridge") }
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
    shouldRunAfter(tasks.test)
}
