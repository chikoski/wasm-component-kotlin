import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    `maven-publish`
}

allprojects {
    group = "io.github.wasm2kt"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
        google()
    }
}

dependencies {
    api(project(":runtime-api"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)

    testImplementation(project(":runtime-chicory"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("compiler-embeddable"))
    testImplementation(libs.junit.jupiter)
}

kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xjdk-release=21",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("wasm2kt.cli.MainKt")
    applicationName = "wasm2kt"
    applicationDefaultJvmArgs = listOf("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
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
