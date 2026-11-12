#!/usr/bin/env bash
# Builds fixtures/async-stream.wasm (+ async-stream.wit) from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse async-stream.wat -o ../../async-stream.wasm
wasm-tools validate --features all ../../async-stream.wasm
wasm-tools component wit ../../async-stream.wasm -o ../../async-stream.wit
