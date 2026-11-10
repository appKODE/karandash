#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v cargo >/dev/null || ! command -v rustup >/dev/null; then
    echo "ERROR: cargo and rustup are required to build the WASM Rust examples (install rustup, then: rustup target add wasm32-unknown-unknown)" >&2
    exit 1
fi

# Ensure wasm32-unknown-unknown target is available via rustup.
# When Homebrew's rustc shadows rustup's, explicitly set RUSTC to
# the rustup-managed compiler so the wasm32 sysroot is found.
if command -v rustup &>/dev/null; then
    TOOLCHAIN_DIR="$(rustup run stable rustc --print sysroot)"
    if [[ -x "$TOOLCHAIN_DIR/bin/rustc" ]]; then
        export RUSTC="$TOOLCHAIN_DIR/bin/rustc"
    fi
fi

cargo build --target wasm32-unknown-unknown --release --manifest-path "$SCRIPT_DIR/Cargo.toml"

RELEASE_DIR="$SCRIPT_DIR/target/wasm32-unknown-unknown/release"

CRATES=(
    karandash_wasm_rule
    karandash_wasm_reporter
    karandash_wasm_provider
    karandash_wasm_mutator
    karandash_wasm_comment_poster
    karandash_wasm_cli_command
    karandash_wasm_config_format
    karandash_wasm_profile
    karandash_wasm_ai_provider
    karandash_wasm_ai_interceptor
    karandash_wasm_ai_analyzer
    karandash_wasm_ai_prompt_processor
    karandash_wasm_ai_response_transformer
    karandash_wasm_ai_context_enricher
    karandash_wasm_ai_redactor
    karandash_wasm_ai_token_allocator
    karandash_wasm_script
)

for crate in "${CRATES[@]}"; do
    cp "$RELEASE_DIR/${crate}.wasm" "$SCRIPT_DIR/${crate}.wasm"
    echo "Built: ${crate}.wasm"
done
