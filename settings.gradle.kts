rootProject.name = "wasm-component-kotlin"

// Code generation (build time)
include(":ast")
include(":generator")
include(":cli")

// Runtimes used by generated bindings
include(":runtime-api")
include(":runtime-wasmtime")
include(":runtime-chicory")

// Chicory build pipeline and Gradle plugin
include(":tooling")
include(":gradle-plugin")

// End-to-end tests across the generator, the CLI and both runtimes
include(":integration-tests")

// Performance measurements (#19); kept out of the default build.
if (providers.gradleProperty("wasm2kt.bench").isPresent) include(":benchmarks")
