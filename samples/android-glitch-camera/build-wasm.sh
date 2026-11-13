#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PNG_GLITCH_DIR="${PNG_GLITCH_DIR:-/Users/chikoski/png-glitch}"
WASM_TOOLS_BIN="${WASM_TOOLS_BIN:-wasm-tools}"
if ! command -v "$WASM_TOOLS_BIN" >/dev/null 2>&1 && [[ -x "$HOME/.cargo/bin/wasm-tools" ]]; then
  WASM_TOOLS_BIN="$HOME/.cargo/bin/wasm-tools"
fi

cargo build --manifest-path "$PNG_GLITCH_DIR/Cargo.toml" \
  -p png-glitch-component \
  --target wasm32-unknown-unknown \
  --release

mkdir -p "$SCRIPT_DIR/app/src/main/wasm"
CORE_WASM="$PNG_GLITCH_DIR/target/wasm32-unknown-unknown/release/png_glitch_component.wasm"
OUT_WASM="$SCRIPT_DIR/app/src/main/wasm/png-glitch-component.wasm"
if ! "$WASM_TOOLS_BIN" component new "$CORE_WASM" -o "$OUT_WASM" 2>/dev/null; then
  cargo run --offline --manifest-path "$PNG_GLITCH_DIR/Cargo.toml" \
    -p png-glitch-component \
    --example componentize -- \
    "$CORE_WASM" "$OUT_WASM"
fi
