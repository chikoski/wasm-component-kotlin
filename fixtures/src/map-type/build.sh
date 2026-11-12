#!/usr/bin/env bash
# Builds fixtures/map-type.wasm from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse map-type.wat -o ../../map-type.wasm
wasm-tools validate --features all ../../map-type.wasm
wasm-tools component wit ../../map-type.wasm -o ../../map-type.wit
