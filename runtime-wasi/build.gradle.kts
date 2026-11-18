import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Host implementations of WASI 0.3 core interfaces (random, clocks, cli). Kept at JVM 17 so it
// can ship alongside :runtime-api and :runtime-chicory on Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.animalsniffer)
    `maven-publish`
}

val codegenClasspath by configurations.creating

dependencies {
    api(project(":runtime-api"))
    codegenClasspath(project(":cli"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generatedKotlinDir = layout.buildDirectory.dir("generated/sources/wasm2kt/kotlin")
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources/wasm2kt")

val generateWasiBindings by tasks.registering(JavaExec::class) {
    val witJson = layout.projectDirectory.file("wit.json")
    inputs.file(witJson)
    outputs.dir(generatedKotlinDir)
    outputs.dir(generatedResourcesDir)
    classpath = codegenClasspath
    mainClass.set("wasm2kt.cli.MainKt")
    args(
        "--from-json",
        witJson.asFile.absolutePath,
        "--interfaces-only",
        "-p",
        "wasm2kt.wasi",
        "-o",
        generatedKotlinDir.get().asFile.absolutePath,
        "--resources-output",
        generatedResourcesDir.get().asFile.absolutePath,
    )
}

sourceSets {
    main {
        kotlin.srcDir(generatedKotlinDir)
        resources.srcDir(generatedResourcesDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateWasiBindings)
}

tasks.named("processResources") {
    dependsOn(generateWasiBindings)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateWasiBindings)
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

dependencies {
    signature("${libs.android.api.signature.get()}@signature")
}
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}
