rootProject.name = "wasm-component-kotlin"

// Code generation (build time)
include(":ast")
include(":generator")
include(":cli")

// Runtimes used by generated bindings
include(":runtime-api")
include(":runtime-canonical")
include(":runtime-aot")
include(":runtime-wasmtime")
include(":runtime-chicory")
include(":runtime-wasi")

// Chicory build pipeline and Gradle plugin
include(":tooling")
include(":gradle-plugin")

// End-to-end tests across the generator, the CLI and both runtimes
include(":integration-tests")

// Test-only helpers shared by runtime-chicory and integration-tests (not published).
include(":test-support")

// Performance measurements (#19); kept out of the default build.
if (providers.gradleProperty("wasm2kt.bench").isPresent) include(":benchmarks")
