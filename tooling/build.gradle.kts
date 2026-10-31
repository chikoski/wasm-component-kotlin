import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build-time tooling shared by the Gradle plugin: decompose components (running the bundled
// wasm decomposer on Chicory), compile core modules to JVM bytecode, and generate Kotlin sources.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    api(project(":generator"))
    implementation(libs.kotlinpoet)
    implementation(libs.chicory.wasi)
    implementation(libs.chicory.compiler)
    implementation(libs.chicory.build.time.compiler)

    testImplementation(project(":runtime-chicory"))
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
}
