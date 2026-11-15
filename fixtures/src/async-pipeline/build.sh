#!/usr/bin/env bash
# Builds fixtures/async-pipeline.wasm (+ async-pipeline.wit): an async stream and future fixture.
# Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/async_pipeline_fixture.wasm -o ../../async-pipeline.wasm
wasm-tools component wit ../../async-pipeline.wasm -o ../../async-pipeline.wit
wasm-tools validate --features all ../../async-pipeline.wasm
