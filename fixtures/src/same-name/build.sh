#!/usr/bin/env bash
# Builds fixtures/same-name.wasm (+ same-name.wit): interfaces with the same short name, and an
# interface both imported and exported (#32). Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/same_name_fixture.wasm -o ../../same-name.wasm
cp same-name.wit ../../same-name.wit
wasm-tools validate ../../same-name.wasm
