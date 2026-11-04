#!/usr/bin/env bash
# Copies the Component Model reference tests (test/ of WebAssembly/component-model) at a commit into
# conformance/component-model/, with their license. Usage: conformance/update.sh <commit>
# Run the conformance tests afterwards and update conformance/skip.txt (#30).
set -euo pipefail
commit="${1:?usage: conformance/update.sh <commit>}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q
git -C "$work" remote add origin https://github.com/WebAssembly/component-model.git
git -C "$work" fetch -q --depth 1 origin "$commit"
git -C "$work" checkout -q FETCH_HEAD
dest="$here/component-model"
rm -rf "$dest"
mkdir -p "$dest"
cp -R "$work/test" "$dest/test"
cp "$work/LICENSE" "$work/LICENSE-APACHE" "$dest/"
git -C "$work" rev-parse HEAD > "$dest/COMMIT"
echo "Copied WebAssembly/component-model@$(cat "$dest/COMMIT")"
