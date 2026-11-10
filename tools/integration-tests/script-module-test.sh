#!/usr/bin/env bash
# Script Module Integration Test
# Runs the `scripts:` block in sandbox (QuickJS) mode through `validate` on the JVM jar and the native
# binary: the example script must run against the MR, and a script's karandash.fail() must become a
# finding that fails validation. A missing binary is a SKIP, which fails when CI=true.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KARANDASH_JAR="${KARANDASH_JAR:-$PROJECT_ROOT/cli/build/libs/karandash.jar}"
KARANDASH_NATIVE="${KARANDASH_NATIVE:-}"
TEST_MR="${TEST_MR:-$SCRIPT_DIR/fixtures/test-mr.json}"
# Absolute paths: validate runs from inside the project dirs below.
abs() { if [[ -e "$1" ]]; then echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; else echo "$1"; fi; }
KARANDASH_JAR="$(abs "$KARANDASH_JAR")"
[[ -n "$KARANDASH_NATIVE" ]] && KARANDASH_NATIVE="$(abs "$KARANDASH_NATIVE")"
TEST_MR="$(abs "$TEST_MR")"
PASS=0
FAIL=0
SKIP=0

pass() { PASS=$((PASS + 1)); echo "PASS: $1"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL: $1"; }
skip() {
    if [[ "${CI:-}" = "true" ]]; then fail "$1 (skipped, but CI=true)"; return; fi
    SKIP=$((SKIP + 1)); echo "SKIP: $1"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/strict/.karandash" "$WORK/lenient/.karandash"
TITLE="$(grep -m1 '"title"' "$TEST_MR" | sed 's/.*"title": *"\(.*\)".*/\1/')"
for d in strict lenient; do cp "$PROJECT_ROOT/tools/examples/example-script.js" "$WORK/$d/.karandash/checks.js"; done
printf 'karandash.fail("script-module-test marker: " + karandash.mr.title);\n' > "$WORK/strict/.karandash/marker.js"
printf 'scripts:\n  - path: .karandash/checks.js\n  - path: .karandash/marker.js\n' > "$WORK/strict/.karandash/config.yaml"
printf 'scripts:\n  - path: .karandash/checks.js\n' > "$WORK/lenient/.karandash/config.yaml"

# validate_in <runtime> <project dir>: run validate there with an isolated home; sets OUT and CODE
validate_in() {
    if [[ "$1" = "jvm" ]]; then
        OUT=$(cd "$2" && HOME="$WORK/home" java -Duser.home="$WORK/home" -jar "$KARANDASH_JAR" validate --mr "$TEST_MR" 2>&1)
    else
        OUT=$(cd "$2" && HOME="$WORK/home" "$KARANDASH_NATIVE" validate --mr "$TEST_MR" 2>&1)
    fi
    CODE=$?
}

run_checks() {
    local rt="$1"
    validate_in "$rt" "$WORK/strict"
    if grep -Fq "Validated MR: $TITLE" <<< "$OUT"; then pass "[$rt] example script ran against the MR"; else fail "[$rt] example script did not run"; fi
    if grep -Fq "script-module-test marker: $TITLE" <<< "$OUT" && [[ $CODE -ne 0 ]]; then
        pass "[$rt] karandash.fail() becomes a finding and fails validation (exit $CODE)"
    else
        fail "[$rt] karandash.fail() finding missing or exit 0 (exit $CODE)"
        echo "$OUT" | tail -15
    fi
    validate_in "$rt" "$WORK/lenient"
    if [[ $CODE -eq 0 ]] && ! grep -Fq "script-module-test marker" <<< "$OUT"; then
        pass "[$rt] passing script leaves validation green"
    else
        fail "[$rt] passing script failed validation (exit $CODE)"
        echo "$OUT" | tail -15
    fi
}

echo "=== Script Module Integration Test ==="
if [[ -f "$KARANDASH_JAR" ]] && command -v java &>/dev/null; then run_checks jvm; else skip "JVM: $KARANDASH_JAR or java missing"; fi
if [[ -n "$KARANDASH_NATIVE" && -x "$KARANDASH_NATIVE" ]]; then run_checks native; else skip "native: KARANDASH_NATIVE not set or not executable"; fi

echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[[ $FAIL -eq 0 ]]
