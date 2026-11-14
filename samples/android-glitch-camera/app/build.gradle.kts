plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
    id("io.github.wasm2kt")
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

    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
}

baselineProfile {
    // Generating the profile needs a device or an emulator. Pass
    // -PgenerateBaselineProfileOnBuild=false to build a release without one (as samples/android).
    automaticGenerationDuringBuild = providers.gradleProperty("generateBaselineProfileOnBuild")
        .map { it.toBoolean() }
        .getOrElse(true)
    dexLayoutOptimization = true
}
