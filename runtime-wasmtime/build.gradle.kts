import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Wasmtime backend: Kotlin side of rust-bridge/, through the FFM API (JDK 21, preview) or JNI.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

dependencies {
    api(project(":runtime-api"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}
