plugins {
    id("com.android.application")
    id("androidx.baselineprofile")
    id("io.github.wasm2kt")
}

android {
    namespace = "com.example.wasm2kt.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wasm2kt.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Exercise R8 on the generated bindings and the AOT-compiled core modules.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
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
        register("roundtrip") {
            source.set(file("src/main/wasm/roundtrip.wasm"))
            packageName.set("com.example.wasm2kt.sample.roundtrip")
        }
        register("counter") {
            source.set(file("src/main/wasm/counter.wasm"))
            packageName.set("com.example.wasm2kt.sample.counter")
        }
    }
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

baselineProfile {
    automaticGenerationDuringBuild = providers.gradleProperty("generateBaselineProfileOnBuild")
        .map { it.toBoolean() }
        .getOrElse(true)
    dexLayoutOptimization = true
}
