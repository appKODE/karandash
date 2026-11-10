#!/usr/bin/env bash
# Regression: every plugin runtime path keeps an end-to-end check in plugin-examples-test.sh: the standalone
# examples (native-lib C/C++/Go/Rust/Kotlin/Python, JS, WASM C/Rust, Kotlin/JVM JAR) are built before Layer 2, and a
# standalone artifact that is missing at Layer 2d records a row (a FAIL under CI=true) instead of silently dropping that
# runtime from the run. The committed example .wasm binaries stay under a size budget.
cd "$(dirname "$0")/../.." || exit 1
FAIL_PREFIX=plugin-examples
. tools/ci/lib.sh
HARNESS=tools/integration-tests/plugin-examples-test.sh
BUDGET_BYTES=$((1024 * 1024))

builds=$(grep -n 'Standalone plugins (C++)' "$HARNESS" | head -1 | cut -d: -f1)
layer2=$(grep -n '^# ── Layer 2: Runtime verification' "$HARNESS" | head -1 | cut -d: -f1)
[ -n "$builds" ] && [ -n "$layer2" ] || fail "harness markers not found"
[ "$builds" -lt "$layer2" ] || fail "standalone examples are built after Layer 2 validates them"

wasm_bytes=$(git ls-files -z 'tools/examples/*.wasm' | xargs -0 cat | wc -c | tr -d ' ')
[ "$wasm_bytes" -le "$BUDGET_BYTES" ] || fail "committed example .wasm binaries are $wasm_bytes bytes, budget $BUDGET_BYTES"

# Run the harness against an examples dir holding only the WASM C standalone, with a stub native CLI that reports
# every configured rule as run: the WASM rule must validate, and the C native-lib and JVM paths must show as not built.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/examples" "$work/results"
cp -R tools/examples/standalone-wasm-c-plugin "$work/examples/"
# *.wasm is gitignored: build a fresh one so a stale local artifact can't pass where a clean checkout fails
rm -f "$work/examples/standalone-wasm-c-plugin/plugin.wasm"
bash "$work/examples/standalone-wasm-c-plugin/build.sh" > "$work/build.log" 2>&1 \
    || { cat "$work/build.log"; fail "the WASM C standalone did not build (needs emcc, or clang with wasm-ld)"; }
cat > "$work/cli" <<'STUB'
#!/usr/bin/env bash
rule=$(sed -n 2p config.yaml 2>/dev/null | tr -d ' :')
echo "plugin Rule [$rule]: completed"
STUB
chmod +x "$work/cli"
out=$(CI=false PLUGIN_LANG=c,kotlin PLUGIN_VARIANT=none KARANDASH_JAR="$work/none.jar" KARANDASH_NATIVE="$work/cli" \
    RESULTS_DIR="$work/results" MOCK_SERVER="$work/no-mock.py" bash "$HARNESS" "$work/examples" 2>&1)
row() { printf '%s\n' "$out" | grep -E "^│ $1 +│ rule +│ $2 +│ $3"; }
row "C-WASM" "validate-native" "ok" > /dev/null \
    || { printf '%s\n' "$out" | tail -n 40; fail "the WASM C standalone did not validate through the CLI"; }
row "C" "validate" "not built" > /dev/null || fail "a missing C native-lib artifact left no row"
row "Kotlin/JVM" "validate" "not built" > /dev/null || fail "a missing JVM plugin JAR left no row"
echo "plugin-examples OK"
