#!/usr/bin/env bash
# Builds the native rule plugin examples (C, Kotlin/Native, Rust, Go) for the host and checks
# each library exports the rule C ABI. A missing toolchain is a SKIP, which fails when CI=true.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="${EXAMPLES_DIR:-$SCRIPT_DIR/../examples}"
OS=$(uname -s)
ARCH=$(uname -m)
PASSED=0
FAILED=0
SKIPPED=0

if [[ "$OS" = "Darwin" ]]; then EXT=dylib; else EXT=so; fi

EXPECTED_SYMBOLS=(
    karandash_abi_version karandash_module_id karandash_module_name karandash_module_version
    karandash_module_type karandash_initialize karandash_shutdown karandash_contributed_rules
    karandash_validate karandash_free_string
)

pass() { PASSED=$((PASSED + 1)); echo "PASS: $1"; }
fail() { FAILED=$((FAILED + 1)); echo "FAIL: $1"; }
skip() {
    if [[ "${CI:-}" = "true" ]]; then fail "$1 (skipped, but CI=true)"; return; fi
    SKIPPED=$((SKIPPED + 1)); echo "SKIP: $1"
}

# check <name> <library path>
check() {
    local name="$1" lib="$2" symbols sym missing=0
    if [[ ! -f "$lib" ]]; then fail "$name: library not found: $lib"; return; fi
    if [[ "$OS" = "Darwin" ]]; then symbols=$(nm -gU "$lib" 2>/dev/null); else symbols=$(nm -D "$lib" 2>/dev/null); fi
    for sym in "${EXPECTED_SYMBOLS[@]}"; do
        grep -q "$sym" <<< "$symbols" || { echo "  missing symbol: $sym"; missing=$((missing + 1)); }
    done
    if [[ $missing -eq 0 ]]; then pass "$name exports all ${#EXPECTED_SYMBOLS[@]} rule symbols"; else fail "$name: $missing symbol(s) missing"; fi
}

# build <name> <tool> <dir> <command...>
build() {
    local name="$1" tool="$2" dir="$3"
    shift 3
    if ! command -v "$tool" &>/dev/null; then skip "$name: $tool not installed"; return 1; fi
    if ! (cd "$dir" && "$@") > /dev/null 2>&1; then fail "$name: build failed ($*)"; return 1; fi
}

echo "=== Native Plugin Integration Tests ($OS-$ARCH) ==="

c_dir="$EXAMPLES_DIR/standalone-native-plugin"
(cd "$c_dir" && make clean) > /dev/null 2>&1
build "C" make "$c_dir" make && check "C" "$c_dir/libnative-example-rule.$EXT"

kt_dir="$EXAMPLES_DIR/native-plugin-kotlin"
case "$OS-$ARCH" in
    Darwin-arm64) kt_target=MacosArm64 ;;
    Darwin-x86_64) kt_target=MacosX64 ;;
    Linux-x86_64) kt_target=LinuxX64 ;;
    *) kt_target="" ;;
esac
if [[ -z "$kt_target" ]]; then
    skip "Kotlin/Native: no target for $OS-$ARCH"
else
    kt_lower="$(printf '%s' "${kt_target:0:1}" | tr '[:upper:]' '[:lower:]')${kt_target:1}"
    build "Kotlin/Native" java "$kt_dir" ./gradlew "linkReleaseShared$kt_target" --console=plain --no-daemon \
        && check "Kotlin/Native" "$kt_dir/build/bin/$kt_lower/releaseShared/libkotlin_example.$EXT"
fi

rust_dir="$EXAMPLES_DIR/native-plugin-rust"
build "Rust" cargo "$rust_dir" cargo build --release \
    && check "Rust" "$rust_dir/target/release/libkarandash_rust_rule_reporter.$EXT"

go_dir="$EXAMPLES_DIR/native-plugin-go"
case "$OS-$ARCH" in
    Darwin-*) go_target=macos-arm64 ;;
    Linux-aarch64 | Linux-arm64) go_target=linux-arm64 ;;
    *) go_target=linux-x64 ;;
esac
(cd "$go_dir" && make clean) > /dev/null 2>&1
build "Go" go "$go_dir" make "$go_target" && check "Go" "$go_dir/libgo-example.$EXT"

echo "Results: $PASSED passed, $FAILED failed, $SKIPPED skipped"
[[ $FAILED -eq 0 ]]
