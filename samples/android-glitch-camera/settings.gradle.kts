// Sample Android camera glitch app using png-glitch and the wasm2kt Gradle plugin (composite build).
// Run from the repository root:  ./gradlew -p samples/android-glitch-camera :app:assembleDebug
pluginManagement {
    includeBuild("../..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wasm2kt-android-glitch-camera"

// Substitutes io.github.wasm2kt:runtime-chicory with the project in this repository.
includeBuild("../..")
include(":app")
