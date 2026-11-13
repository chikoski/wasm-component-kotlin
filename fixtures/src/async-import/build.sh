#!/usr/bin/env bash
# Builds fixtures/async-import.wasm (+ async-import.wit) from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse async-import.wat -o ../../async-import.wasm
wasm-tools validate --features all ../../async-import.wasm
wasm-tools component wit ../../async-import.wasm -o ../../async-import.wit
