import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared runtime contract for generated bindings. Kept at JVM 11 so it can ship to Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    api(libs.kotlinx.serialization.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        // Used by the Gradle plugin's functional tests; real publishing is configured separately.
        maven {
            name = "test"
            url = uri(rootProject.layout.buildDirectory.dir("test-repo"))
        }
    }
}
