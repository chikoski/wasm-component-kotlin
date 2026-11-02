// Sample Android app using the wasm2kt Gradle plugin from this repository (composite build).
// Run from the repository root:  ./gradlew -p samples/android :app:assembleDebug
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

rootProject.name = "wasm2kt-android-sample"

// Substitutes io.github.wasm2kt:runtime-chicory with the project in this repository.
includeBuild("../..")
include(":app")
include(":benchmark")
