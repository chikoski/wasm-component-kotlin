#!/usr/bin/env bash
# Regenerates runtime-chicory's test components (plan.json, wit.json, core modules) from the
# component fixtures with the current decomposer. Run after changing the decomposer or the plan
# format; CI fails if the committed output is stale.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build --locked --release --manifest-path decomposer/Cargo.toml
for name in abi-edge arithmetic async-import async-stream composed-str counter host-res live_calc res-edge roundtrip same-name types; do
  out="runtime-chicory/src/test/resources/components/$name"
  rm -rf "$out"
  decomposer/target/release/wasm2kt-decompose "fixtures/$name.wasm" "$out"
done
