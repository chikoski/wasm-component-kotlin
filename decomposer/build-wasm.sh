#!/usr/bin/env bash
# Rebuilds the decomposer as WebAssembly and refreshes the copy bundled with the Gradle plugin.
# The plugin runs it on Chicory, so building components needs no Rust toolchain or native binary.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-wasip1 >/dev/null
cargo build --release --target wasm32-wasip1
mkdir -p dist
cp target/wasm32-wasip1/release/wasm2kt-decompose.wasm dist/
shasum -a 256 dist/wasm2kt-decompose.wasm
