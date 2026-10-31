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
