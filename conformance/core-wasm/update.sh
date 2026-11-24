#!/usr/bin/env bash
# Copies Core WebAssembly 2.0 reference tests (test/core/*.wast of WebAssembly/spec, excluding
# simd/) at a commit into conformance/core-wasm/spec/, with their license.
# Usage: conformance/core-wasm/update.sh [commit]
set -euo pipefail
commit="${1:-fffc6e12fa454e475455a7b58d3b5dc343980c10}"
here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
tmp_base="${TMPDIR:-$repo_root/.tmp}"
mkdir -p "$tmp_base"
work="$(mktemp -d "$tmp_base/core-wasm.XXXXXX")"
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q
git -C "$work" remote add origin https://github.com/WebAssembly/spec.git
git -C "$work" fetch -q --depth 1 origin "$commit"
git -C "$work" checkout -q FETCH_HEAD
dest="$here/spec"
rm -rf "$dest"
mkdir -p "$dest"
cp "$work"/test/core/*.wast "$dest/"
if [ -f "$work/LICENSE" ]; then
    cp "$work/LICENSE" "$dest/"
fi
git -C "$work" rev-parse HEAD > "$dest/COMMIT"
echo "Copied WebAssembly/spec@$(cat "$dest/COMMIT") ($(ls "$dest"/*.wast | wc -l | tr -d ' ') .wast files)"
