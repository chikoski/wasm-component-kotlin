// Performance measurements on ART (#19, docs/performance.md): Jetpack Microbenchmark for per-call
// costs and a cold-start probe, over the same components as the JVM benchmarks (benchmarks/).
// Run on a device or emulator with .github/workflows/bench.yml, or by hand:
//   ./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest
// then install and `am instrument` it (see bench.yml).
import wasm2kt.gradle.Wasm2KtDecomposeTask

plugins {
    id("com.android.library")
    id("io.github.wasm2kt")
}

android {
    namespace = "com.example.wasm2kt.bench"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // An emulator is fine for relative numbers; the report says where they come from.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,UNLOCKED"
    }

    // Benchmarks must not run debuggable code: test the release build type (not minified).
    testBuildType = "release"
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

val benchComponents = listOf("arithmetic", "types", "heavy")

wasm2kt {
    components {
        for (name in benchComponents) {
            register(name) {
                source.set(rootProject.file("../../fixtures/$name.wasm"))
                packageName.set("com.example.wasm2kt.bench.$name")
            }
        }
    }
}

interface BenchComponentInput {
    @get:Input
    val componentName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val decomposedDir: DirectoryProperty
}

abstract class CollectBenchAssetsTask : DefaultTask() {
    @get:Nested
    abstract val entries: ListProperty<BenchComponentInput>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun collect() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (entry in entries.get()) {
            val dest = out.resolve("decomposed/${entry.componentName.get()}")
            dest.mkdirs()
            entry.decomposedDir.get().asFile.copyRecursively(dest, overwrite = true)
        }
    }
}

// The decomposed core modules as assets, to compare against Chicory's interpreter on ART.
androidComponents.onVariants { variant ->
    variant.deviceTests.forEach { (deviceTestName, deviceTest) ->
        val variantCap = variant.name.replaceFirstChar(Char::uppercaseChar)
        val testCap = deviceTestName.replaceFirstChar(Char::uppercaseChar)
        val taskName = "collectBenchAssets$variantCap$testCap"
        val benchAssets = tasks.register(taskName, CollectBenchAssetsTask::class.java) {
            for (name in benchComponents) {
                val cap = name.replaceFirstChar(Char::uppercaseChar)
                val decomposeDir = tasks.named("wasm2ktDecompose$cap", Wasm2KtDecomposeTask::class.java)
                    .flatMap { task -> task.outputDir }
                entries.add(
                    objects.newInstance(BenchComponentInput::class.java).apply {
                        componentName.set(name)
                        decomposedDir.set(decomposeDir)
                    },
                )
            }
        }
        val assets = checkNotNull(deviceTest.sources.assets) {
            "Device test $deviceTestName of variant ${variant.name} has no assets source directories"
        }
        assets.addGeneratedSourceDirectory(benchAssets, CollectBenchAssetsTask::outputDir)
    }
}

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.3.4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
