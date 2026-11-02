#!/usr/bin/env bash
# Builds fixtures/composed-str.wasm (+ composed-str.wit) from the generated WAT. Needs python3 and wasm-tools.
set -euo pipefail
cd "$(dirname "$0")"
python3 gen-wat.py
wasm-tools parse composed-str.wat -o ../../composed-str.wasm
wasm-tools validate ../../composed-str.wasm
wasm-tools component wit ../../composed-str.wasm -o ../../composed-str.wit
