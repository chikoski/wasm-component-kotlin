import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val functionalTest by sourceSets.creating

dependencies {
    // Only for compiling the worker actions: at build time the tooling is resolved through the
    // consumer's `wasm2ktTooling` configuration and loaded in an isolated class loader.
    compileOnly(project(":tooling"))
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)

    // Generates the interface library a functional test maps to (ADR-0014).
    "functionalTestImplementation"(project(":generator"))
    "functionalTestImplementation"(kotlin("test"))
    "functionalTestImplementation"(libs.junit.jupiter)
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

gradlePlugin {
    plugins {
        create("wasm2kt") {
            id = "io.github.wasm2kt"
            implementationClass = "wasm2kt.gradle.Wasm2KtPlugin"
            displayName = "wasm2kt"
            description = "Kotlin host bindings for WebAssembly components, running on Chicory with core modules compiled at build time"
        }
    }
}

// Lets the plugin add a matching runtime dependency to consuming projects.
val writeVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/wasm2kt-version")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("wasm2kt/gradle/wasm2kt-plugin.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$version\n")
    }
}
sourceSets.main { resources.srcDir(writeVersion) }

// Functional tests run real builds with TestKit against artifacts published to a local repository.
gradlePlugin.testSourceSets(functionalTest)
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

val testRepo = rootProject.layout.buildDirectory.dir("test-repo")
val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs builds that apply the plugin with Gradle TestKit."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    // A failing TestKit build carries its whole output in the exception message: print it, so a CI
    // failure can be diagnosed from the job log (the report artifact is not always at hand).
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
    dependsOn(
        ":ast:publishAllPublicationsToTestRepository",
        ":generator:publishAllPublicationsToTestRepository",
        ":runtime-api:publishAllPublicationsToTestRepository",
        ":runtime-chicory:publishAllPublicationsToTestRepository",
        ":runtime-wasi:publishAllPublicationsToTestRepository",
        ":tooling:publishAllPublicationsToTestRepository",
        ":gradle-plugin:publishAllPublicationsToTestRepository",
    )
    systemProperty("wasm2kt.testRepo", testRepo.get().asFile.absolutePath)
    systemProperty("wasm2kt.version", project.version.toString())
    systemProperty("wasm2kt.fixtures", rootProject.file("fixtures").absolutePath)
    systemProperty("wasm2kt.examples", rootProject.file("examples").absolutePath)
    systemProperty("wasm2kt.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("wasm2kt.agpVersion", libs.versions.android.gradle.api.get())
    // Optional: reuse a Gradle user home (caches, init scripts) for the builds under test.
    systemProperty("wasm2kt.testKitDir", providers.gradleProperty("wasm2kt.testKitDir").getOrElse(""))
}
tasks.check { dependsOn(functionalTestTask) }
