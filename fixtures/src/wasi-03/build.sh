#!/usr/bin/env bash
# Builds fixtures/wasi-03.wasm (+ wasi-03.wit): a Rust WASI 0.3 component importing
# custom + core WASI 0.3 interfaces (random, clocks, cli, filesystem) and async streams.
# Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/wasi_03_fixture.wasm -o ../../wasi-03.wasm
wasm-tools validate --features all ../../wasi-03.wasm
wasm-tools component wit ../../wasi-03.wasm -o ../../wasi-03.wit
