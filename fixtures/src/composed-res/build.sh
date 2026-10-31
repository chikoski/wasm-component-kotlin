#!/usr/bin/env bash
# Builds fixtures/composed-res.wasm (+ composed-res.wit) from the hand-written WAT. Needs wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
wasm-tools parse composed-res.wat -o ../../composed-res.wasm
wasm-tools validate ../../composed-res.wasm
wasm-tools component wit ../../composed-res.wasm -o ../../composed-res.wit
