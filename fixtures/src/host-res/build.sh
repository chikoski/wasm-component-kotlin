#!/usr/bin/env bash
# Builds fixtures/host-res.wasm (+ host-res.wit): a component importing host-defined resources.
# Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/host_res_fixture.wasm -o ../../host-res.wasm
cp host-res.wit ../../host-res.wit
wasm-tools validate ../../host-res.wasm
