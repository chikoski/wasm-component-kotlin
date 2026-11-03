#!/usr/bin/env bash
# Builds fixtures/heavy.wasm (+ heavy.wit): a large component (Rust std + regex + serde_json) used by
# the performance and size measurements (docs/performance.md). Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/heavy_fixture.wasm -o ../../heavy.wasm
cp heavy.wit ../../heavy.wit
wasm-tools validate ../../heavy.wasm
