#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT_DIR:-$SCRIPT_DIR}/plugin.wasm"
mkdir -p "$(dirname "$OUT")"
EXPORTS='["_karandash_abi_version","_karandash_alloc","_karandash_dealloc","_karandash_module_id","_karandash_module_name","_karandash_module_version","_karandash_module_type","_karandash_describe","_karandash_initialize","_karandash_shutdown","_karandash_contributed_rules","_karandash_validate","_karandash_format_id","_karandash_report","_karandash_profile_id","_karandash_profile_summary","_karandash_profile_tags","_karandash_profile_rule_configs","_karandash_profile_reporter_configs","_karandash_cli_commands","_karandash_run_cli_command"]'

if command -v emcc &>/dev/null; then
    echo "Building with Emscripten..."
    emcc "$SCRIPT_DIR/plugin.c" \
        -O2 \
        -s STANDALONE_WASM \
        -s "EXPORTED_FUNCTIONS=$EXPORTS" \
        -s ERROR_ON_UNDEFINED_SYMBOLS=0 \
        --no-entry \
        -o "$OUT"
    echo "Built: $OUT"
    exit 0
fi

if command -v clang &>/dev/null; then
    echo "Building with clang --target=wasm32..."
    clang \
        --target=wasm32 \
        -nostdlib \
        -Wl,--no-entry \
        -Wl,--export-all \
        -O2 \
        -o "$OUT" \
        "$SCRIPT_DIR/plugin.c" \
        "$SCRIPT_DIR/wasm-builtins.c"
    echo "Built: $OUT"
    exit 0
fi

echo "ERROR: Neither emcc nor clang found. Install Emscripten or LLVM with wasm32 support."
exit 1
