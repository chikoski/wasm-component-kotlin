import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build-time tooling shared by the Gradle plugin: decompose components (running the bundled
// wasm decomposer on Chicory), compile core modules to JVM bytecode, and generate Kotlin sources.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    api(project(":generator"))
    implementation(project(":runtime-canonical"))
    implementation(libs.kotlinpoet)
    implementation(libs.chicory.wasi)
    implementation(libs.chicory.compiler)
    implementation(libs.chicory.build.time.compiler)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)
    constraints {
        val asmVersion = libs.versions.asm.get()
        for (module in listOf(libs.asm, libs.asm.tree, libs.asm.analysis, libs.asm.util)) {
            implementation(module) {
                version { strictly(asmVersion) }
                because("ASM 9.11+ hardcodes ComputeLimits in ClassWriter, which fails in Chicory 1.7.5 on large modules")
            }
        }
    }

    testImplementation(project(":runtime-wasmtime"))
    testImplementation(project(":runtime-chicory"))
    testImplementation(project(":runtime-wasi"))
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

// The decomposer ships as WebAssembly (built by decomposer/build-wasm.sh).
tasks.processResources {
    from(rootProject.file("decomposer/dist/wasm2kt-decompose.wasm")) {
        into("wasm2kt/tooling")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("wasm2kt.fixtures", rootProject.file("fixtures").absolutePath)
    systemProperty("wasm2kt.examples", rootProject.file("examples").absolutePath)
    systemProperty("wasm2kt.rootDir", rootProject.projectDir.absolutePath)
}

// The typed bridges carry the generic bridge's core crate and lock file (ADR-0016).
val bridgeCore = rootProject.file("rust-bridge")
val bridgeCoreFiles = tasks.register("bridgeCoreFileList") {
    val out = layout.buildDirectory.file("generated/bridge-core/wasm2kt/bridge-core/files.txt")
    inputs.dir(bridgeCore.resolve("core/src"))
    inputs.file(bridgeCore.resolve("core/Cargo.toml"))
    outputs.file(out)
    doLast {
        val root = bridgeCore.resolve("core")
        val names = listOf("Cargo.toml") + root.resolve("src").walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(names.joinToString("\n", postfix = "\n"))
    }
}
tasks.processResources {
    dependsOn(bridgeCoreFiles)
    from(layout.buildDirectory.dir("generated/bridge-core"))
    from(bridgeCore.resolve("core")) {
        include("Cargo.toml", "src/**")
        into("wasm2kt/bridge-core/core")
    }
    from(bridgeCore) {
        include("Cargo.toml", "Cargo.lock")
        into("wasm2kt/bridge-core")
    }
}
