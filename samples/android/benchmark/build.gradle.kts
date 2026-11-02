// Performance measurements on ART (#19, docs/performance.md): Jetpack Microbenchmark for per-call
// costs and a cold-start probe, over the same components as the JVM benchmarks (benchmarks/).
// Run on a device or emulator with .github/workflows/bench.yml, or by hand:
//   ./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest
// then install and `am instrument` it (see bench.yml).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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

    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("bench-assets"))
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

// The decomposed core modules as assets, to compare against Chicory's interpreter on ART.
val benchAssets by tasks.registering(Sync::class) {
    for (name in benchComponents) {
        val cap = name.replaceFirstChar(Char::uppercaseChar)
        from(tasks.named("wasm2ktDecompose$cap")) { into("decomposed/$name") }
    }
    into(layout.buildDirectory.dir("bench-assets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach { dependsOn(benchAssets) }

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.3.4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
