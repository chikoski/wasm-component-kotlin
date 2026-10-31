#!/usr/bin/env bash
# Builds fixtures/res-edge.wasm (+ res-edge.wit) from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse res-edge.wat -o ../../res-edge.wasm
wasm-tools validate ../../res-edge.wasm
wasm-tools component wit ../../res-edge.wasm -o ../../res-edge.wit
