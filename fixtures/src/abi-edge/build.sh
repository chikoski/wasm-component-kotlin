#!/usr/bin/env bash
# Builds fixtures/abi-edge.wasm (+ abi-edge.wit) from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse abi-edge.wat -o ../../abi-edge.wasm
wasm-tools validate ../../abi-edge.wasm
wasm-tools component wit ../../abi-edge.wasm -o ../../abi-edge.wit
