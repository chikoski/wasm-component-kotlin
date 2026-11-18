import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
    id("io.github.wasm2kt")
}

abstract class DownloadSelfieSegmenterTask : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val outFile = dir.resolve("selfie_segmenter.tflite")
        val expected = expectedSha256.get().lowercase()
        if (outFile.isFile && sha256Hex(outFile.readBytes()) == expected) {
            return
        }
        val tmpFile = dir.resolve("selfie_segmenter.tflite.tmp")
        var connection: HttpURLConnection? = null
        try {
            val opened = URI(modelUrl.get()).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            connection = opened as? HttpURLConnection
            opened.getInputStream().use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val actual = sha256Hex(tmpFile.readBytes())
            if (actual != expected) {
                error("SHA-256 mismatch for selfie_segmenter.tflite: expected $expected, got $actual")
            }
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            connection?.disconnect()
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

val downloadSelfieSegmenterModel = tasks.register<DownloadSelfieSegmenterTask>("downloadSelfieSegmenterModel") {
    modelUrl.set("https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite")
    expectedSha256.set("191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b")
    outputDir.set(layout.buildDirectory.dir("generated/mediapipeAssets"))
}

android {
    namespace = "com.example.wasm2kt.glitchcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wasm2kt.glitchcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

wasm2kt {
    components {
        register("pngGlitch") {
            source.set(file("src/main/wasm/png-glitch-component.wasm"))
            packageName.set("com.example.wasm2kt.glitchcamera.pngglitch")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val cameraVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadSelfieSegmenterModel,
            DownloadSelfieSegmenterTask::outputDir,
        )
    }
}

baselineProfile {
    // Generating the profile needs a device or an emulator. Pass
    // -PgenerateBaselineProfileOnBuild=false to build a release without one (as samples/android).
    automaticGenerationDuringBuild = providers.gradleProperty("generateBaselineProfileOnBuild")
        .map { it.toBoolean() }
        .getOrElse(true)
    dexLayoutOptimization = true
}
