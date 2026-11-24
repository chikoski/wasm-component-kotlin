import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Chicory-independent Canonical ABI layer shared by runtime-chicory and runtime-aot.
// Kept at JVM 17 and checked against Android API 26.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.animalsniffer)
    `maven-publish`
}

dependencies {
    api(project(":runtime-api"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    signature("${libs.android.api.signature.get()}@signature")
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}
