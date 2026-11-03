import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Performance and size measurements (#19, docs/performance.md). Not part of `check`: the module is
// only included with -Pwasm2kt.bench (see settings.gradle.kts).
//
//   ./gradlew -Pwasm2kt.bench :benchmarks:bench        # steady-state per-call costs
//   ./gradlew -Pwasm2kt.bench :benchmarks:coldStart    # first instantiation, in fresh JVMs
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val prepare by sourceSets.creating

dependencies {
    "prepareImplementation"(project(":tooling"))
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

kotlin.sourceSets.named("main") { kotlin.srcDir(benchGen.map { it.dir("kotlin") }) }
val generatedClasses = files(benchGen.map { it.dir("classes") }, benchGen.map { it.dir("resources") }).builtBy(prepareBench)

dependencies {
    implementation(project(":runtime-chicory"))
    implementation(project(":runtime-wasmtime"))
    implementation(generatedClasses)
}

tasks.named("compileKotlin") { dependsOn(prepareBench) }

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

tasks.register<JavaExec>("bench") {
    description = "Measures steady-state per-call costs on every backend."
    mainClass.set("wasm2kt.bench.CallBenchKt")
    benchJvm()
}

tasks.register<JavaExec>("coldStart") {
    description = "Measures first instantiation and first call in fresh JVMs."
    mainClass.set("wasm2kt.bench.ColdStartKt")
    benchJvm()
}

tasks.register<JavaExec>("profile") {
    description = "Samples where a heavy Chicory AOT call spends its time."
    mainClass.set("wasm2kt.bench.ProfileKt")
    benchJvm()
}
