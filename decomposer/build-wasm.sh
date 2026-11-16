#!/usr/bin/env bash
# Rebuilds the decomposer as WebAssembly and refreshes the copy bundled with the Gradle plugin.
# The plugin runs it on Chicory, so building components needs no Rust toolchain or native binary.
#
# The build is reproducible: the toolchain is pinned in rust-toolchain.toml, dependencies in
# Cargo.lock, and machine-specific paths (Cargo home, checkout location) are remapped so they don't
# end up in panic messages. CI rebuilds it and fails if dist/ differs (see .github/workflows/ci.yml).
set -euo pipefail
cd "$(dirname "$0")"
here="$(pwd -P)"
cargo_home="$(cd "${CARGO_HOME:-$HOME/.cargo}" && pwd -P)"

# Cargo hashes `rustc -vV` (including `host:`) into every crate's `-C metadata`, which
# changes mangled symbol hashes and LTO ordering across host OSes. Normalize `host:` so
# macOS and Linux CI produce a byte-identical module.
rustc_bin="$(rustup which rustc)"
mkdir -p target
rustc_wrapper="${here}/target/rustc-linux-host.sh"
cat >"$rustc_wrapper" <<EOF
#!/usr/bin/env bash
if [ "\$#" -eq 1 ] && [ "\$1" = "-vV" ]; then
  "$rustc_bin" -vV | sed 's/^host: .*/host: x86_64-unknown-linux-gnu/'
  exit 0
fi
exec "$rustc_bin" "\$@"
EOF
chmod +x "$rustc_wrapper"

export RUSTC="$rustc_wrapper"
export RUSTFLAGS="--remap-path-prefix=${cargo_home}=/cargo --remap-path-prefix=${here}=/decomposer"
export SOURCE_DATE_EPOCH=0
cargo build --locked --release --target wasm32-wasip1

mkdir -p dist
cp target/wasm32-wasip1/release/wasm2kt-decompose.wasm dist/
if command -v sha256sum >/dev/null; then sha256sum dist/wasm2kt-decompose.wasm; else shasum -a 256 dist/wasm2kt-decompose.wasm; fi
