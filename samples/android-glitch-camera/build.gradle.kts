// AGP 8.9 bundles R8 8.9, which cannot read the Kotlin metadata of Kotlin 2.2 ("An error occurred
// when parsing kotlin metadata"). R8 8.11 supports Kotlin 2.2:
// https://developer.android.com/studio/build/kotlin-d8-r8-versions
// Remove this once AGP is 8.11 or later (which needs Gradle 8.13 or later).
buildscript {
    repositories {
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:8.11.32")
    }
}

plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.test") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
}
