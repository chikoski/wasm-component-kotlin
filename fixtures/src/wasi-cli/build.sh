#!/usr/bin/env bash
# Builds fixtures/wasi-cli.wasm (+ wasi-cli.wit): a WASI 0.3 CLI fixture exporting wasi:cli/run.
# Needs Rust and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add wasm32-unknown-unknown >/dev/null
cargo build --locked --release --target wasm32-unknown-unknown
wasm-tools component new target/wasm32-unknown-unknown/release/wasi_cli_fixture.wasm -o ../../wasi-cli.wasm
wasm-tools component wit ../../wasi-cli.wasm -o ../../wasi-cli.wit
wasm-tools validate --features all ../../wasi-cli.wasm
