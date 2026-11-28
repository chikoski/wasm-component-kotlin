import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Performance and size measurements (#19, docs/performance.md). Not part of `check`: the module is
// only included with -Pwasm2kt.bench (see settings.gradle.kts).
//
//   ./gradlew -Pwasm2kt.bench :benchmarks:bench        # steady-state per-call costs (sync)
//   ./gradlew -Pwasm2kt.bench :benchmarks:coldStart    # first instantiation, in fresh JVMs (sync)
//   ./gradlew -Pwasm2kt.bench :benchmarks:benchTyped   # Wasmtime's typed bridge (needs cargo)
//   ./gradlew -Pwasm2kt.bench :benchmarks:benchAsync   # steady-state suspending calls (async + WASI 0.3)
//   ./gradlew -Pwasm2kt.bench :benchmarks:coldStartAsync # cold start in fresh JVMs (async + WASI 0.3)
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val prepare by sourceSets.creating
val asyncBench by sourceSets.creating

dependencies {
    "prepareImplementation"(project(":tooling"))
    "prepareImplementation"(project(":runtime-wasi"))
    "prepareImplementation"(libs.chicory.wasm)
    "prepareImplementation"(libs.chicory.build.time.compiler)
    "prepareImplementation"(libs.chicory.compiler)
}

val benchGen = layout.buildDirectory.dir("bench-gen")
val benchComponents = listOf("arithmetic", "types", "heavy")

val prepareBench by tasks.registering(JavaExec::class) {
    description = "Decomposes, AOT-compiles and generates bindings for the benchmark components."
    classpath = prepare.runtimeClasspath
    mainClass.set("wasm2kt.bench.PrepareKt")
    val fixtures = rootProject.layout.projectDirectory.dir("fixtures")
    inputs.files(benchComponents.map { fixtures.file("$it.wasm") })
    outputs.dir(benchGen)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(fixtures.asFile.path, benchGen.get().asFile.path) + benchComponents
    })
    doFirst { benchGen.get().asFile.deleteRecursively() }
}

val asyncBenchGen = layout.buildDirectory.dir("bench-async-gen")
val asyncBenchComponents = listOf("async-stream", "async-pipeline", "wasi-03")

val prepareAsyncBench by tasks.registering(JavaExec::class) {
    description = "Builds async and WASI 0.3 benchmark components on both Chicory and AOT."
    classpath = prepare.runtimeClasspath
    mainClass.set("wasm2kt.bench.PrepareAsyncKt")
    val fixtures = rootProject.layout.projectDirectory.dir("fixtures")
    inputs.files(asyncBenchComponents.map { fixtures.file("$it.wasm") })
    outputs.dir(asyncBenchGen)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(fixtures.asFile.path, asyncBenchGen.get().asFile.path) + asyncBenchComponents
    })
    doFirst { asyncBenchGen.get().asFile.deleteRecursively() }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(benchGen.map { it.dir("kotlin") }) }
val generatedClasses = files(benchGen.map { it.dir("classes") }, benchGen.map { it.dir("resources") }).builtBy(prepareBench)

kotlin.sourceSets.named("asyncBench") { kotlin.srcDir(asyncBenchGen.map { it.dir("kotlin") }) }
val generatedAsyncClasses = files(asyncBenchGen.map { it.dir("classes") }, asyncBenchGen.map { it.dir("resources") }).builtBy(prepareAsyncBench)

dependencies {
    implementation(project(":tooling"))
    implementation(project(":runtime-aot"))
    implementation(project(":runtime-chicory"))
    implementation(project(":runtime-wasmtime"))
    implementation(generatedClasses)

    "asyncBenchImplementation"(sourceSets.main.get().output)
    "asyncBenchImplementation"(project(":runtime-api"))
    "asyncBenchImplementation"(project(":runtime-canonical"))
    "asyncBenchImplementation"(project(":runtime-aot"))
    "asyncBenchImplementation"(project(":runtime-chicory"))
    "asyncBenchImplementation"(project(":runtime-wasi"))
    "asyncBenchImplementation"(libs.kotlinx.coroutines.core)
    "asyncBenchImplementation"(generatedAsyncClasses)
}

tasks.named("compileKotlin") { dependsOn(prepareBench) }
tasks.named("compileAsyncBenchKotlin") { dependsOn(prepareAsyncBench) }

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

fun JavaExec.benchJvm() {
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootDir
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g")
    // -Pwasm2kt.bench.jvmArgs="-XX:-DontCompileHugeMethods" etc. for experiments.
    providers.gradleProperty("wasm2kt.bench.jvmArgs").orNull?.let { jvmArgs(it.split(' ')) }
    systemProperty("wasm2kt.bench.gen", benchGen.get().asFile.path)
    systemProperty("wasm2kt.bench.out", layout.buildDirectory.dir("results").get().asFile.path)
    providers.gradleProperty("wasm2kt.bench.filter").orNull?.let { systemProperty("wasm2kt.bench.filter", it) }
    providers.gradleProperty("wasm2kt.bench.outFile").orNull?.let { systemProperty("wasm2kt.bench.outFile", it) }
}

fun JavaExec.asyncBenchJvm() {
    classpath = asyncBench.runtimeClasspath
    workingDir = rootDir
    jvmArgs("-Xms1g", "-Xmx1g")
    providers.gradleProperty("wasm2kt.bench.jvmArgs").orNull?.let { jvmArgs(it.split(' ')) }
    systemProperty("wasm2kt.bench.gen", asyncBenchGen.get().asFile.path)
    systemProperty("wasm2kt.bench.out", layout.buildDirectory.dir("results").get().asFile.path)
    providers.gradleProperty("wasm2kt.bench.filter").orNull?.let { systemProperty("wasm2kt.bench.filter", it) }
    providers.gradleProperty("wasm2kt.bench.outFile").orNull?.let { systemProperty("wasm2kt.bench.outFile", it) }
}

tasks.register<JavaExec>("bench") {
    description = "Measures steady-state per-call costs on every backend."
    mainClass.set("wasm2kt.bench.CallBenchKt")
    benchJvm()
}

tasks.register<JavaExec>("benchTyped") {
    description = "Measures the Wasmtime typed bridge (ADR-0016) against the generic path; builds it with cargo."
    mainClass.set("wasm2kt.bench.TypedBenchKt")
    benchJvm()
}

tasks.register<JavaExec>("coldStart") {
    description = "Measures first instantiation and first call in fresh JVMs."
    mainClass.set("wasm2kt.bench.ColdStartKt")
    benchJvm()
}

tasks.register<JavaExec>("benchAsync") {
    description = "Measures steady-state async and WASI 0.3 costs on Chicory and AOT."
    mainClass.set("wasm2kt.bench.AsyncBenchKt")
    asyncBenchJvm()
}

tasks.register<JavaExec>("coldStartAsync") {
    description = "Measures cold start for async and WASI 0.3 components in fresh JVMs."
    mainClass.set("wasm2kt.bench.AsyncColdStartKt")
    asyncBenchJvm()
}

tasks.register<JavaExec>("profile") {
    description = "Samples where a heavy Chicory AOT call spends its time."
    mainClass.set("wasm2kt.bench.ProfileKt")
    benchJvm()
}
