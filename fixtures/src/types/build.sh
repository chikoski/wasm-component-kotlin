#!/usr/bin/env bash
# Builds fixtures/types.wasm (+ types.wit) from this crate: a core module compiled with wit-bindgen,
# wrapped into a component with `wasm-tools component new`. Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/types_fixture.wasm -o ../../types.wasm
cp types.wit ../../types.wit
wasm-tools validate ../../types.wasm
