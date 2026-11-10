#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v cargo >/dev/null || ! command -v rustup >/dev/null; then
    echo "ERROR: cargo and rustup are required to build the WASM Rust examples (install rustup, then: rustup target add wasm32-unknown-unknown)" >&2
    exit 1
fi

if ! rustup target list --installed | grep -q wasm32-unknown-unknown; then
    echo "Adding wasm32-unknown-unknown target..."
    rustup target add wasm32-unknown-unknown
fi

# When Homebrew's rustc shadows rustup's, use the rustup-managed compiler so the wasm32 sysroot is found.
TOOLCHAIN_DIR="$(rustup run stable rustc --print sysroot)"
if [[ -x "$TOOLCHAIN_DIR/bin/rustc" ]]; then
    export RUSTC="$TOOLCHAIN_DIR/bin/rustc"
fi

echo "Building WASM plugin..."
cargo build --manifest-path "$SCRIPT_DIR/Cargo.toml" \
    --target wasm32-unknown-unknown \
    --release

OUT="${OUT_DIR:-$SCRIPT_DIR}/plugin.wasm"
mkdir -p "$(dirname "$OUT")"
cp "$SCRIPT_DIR/target/wasm32-unknown-unknown/release/karandash_standalone_wasm_rust.wasm" "$OUT"

echo "Built: $OUT"
