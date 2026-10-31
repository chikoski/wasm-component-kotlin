// Aggregator only: code lives in the subprojects listed in settings.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "io.github.wasm2kt"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
        google()
    }
}

// Every library module that applies `maven-publish` gets a Maven publication with sources, and the
// local "test" repository used by the Gradle plugin's functional tests (real publishing: #10).
subprojects {
    plugins.withId("maven-publish") {
        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> { withSourcesJar() }
        }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
            repositories {
                maven {
                    name = "test"
                    url = uri(rootProject.layout.buildDirectory.dir("test-repo"))
                }
            }
        }
    }
}
