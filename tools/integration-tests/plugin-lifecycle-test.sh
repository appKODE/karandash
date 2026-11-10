#!/usr/bin/env bash
#
# Integration test: plugin lifecycle - install, list, use, remove, re-install.
#
# Builds a rule plugin from `karandash plugin create` and drives it through the CLI
# in an isolated home (plugins load from <home>/.karandash/plugins).
#
# Env:
#   KARANDASH_JAR     CLI fat JAR (default: cli/build/libs/karandash.jar)
#   TEST_MR           MR fixture (default: tools/integration-tests/fixtures/test-mr.json)
#   SKIP_SDK_PUBLISH  1 = the SDK is already in mavenLocal
#   CI                true = a SKIP counts as a failure
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
KARANDASH_JAR="$(cd "$PROJECT_ROOT" && realpath "${KARANDASH_JAR:-cli/build/libs/karandash.jar}" 2>/dev/null || true)"
TEST_MR="$(cd "$PROJECT_ROOT" && realpath "${TEST_MR:-tools/integration-tests/fixtures/test-mr.json}" 2>/dev/null || true)"

PLUGIN_NAME="lifecycle"
PLUGIN_ID="$PLUGIN_NAME-plugin"
FINDING="  ${PLUGIN_NAME}Check"

PASS=0
FAIL=0
SKIP=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
skip() {
    if [ "${CI:-}" = "true" ]; then
        fail "$1 (SKIP is a failure when CI=true)"
    else
        echo "  SKIP: $1"
        SKIP=$((SKIP + 1))
    fi
}
# check <description> <command...> - passes when the command succeeds
check() {
    local desc="$1"
    shift
    if "$@"; then pass "$desc"; else fail "$desc"; fi
}

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM
HOME_DIR="$TEMP_DIR/home"
PLUGIN_DIR="$HOME_DIR/.karandash/plugins"
WORK="$TEMP_DIR/work"
mkdir -p "$HOME_DIR" "$WORK"

# The CLI with an isolated home, run in $WORK (validate only reads configs under the working directory).
k() { (cd "$WORK" && HOME="$HOME_DIR" java -Duser.home="$HOME_DIR" -jar "$KARANDASH_JAR" "$@" 2>&1 | grep -v SLF4J; exit "${PIPESTATUS[0]}"); }
listed() { k plugin list | grep -q "$PLUGIN_ID"; }
not_listed() { ! listed; }
listed_version() { k plugin list | grep "$PLUGIN_ID" | grep -q "v$1"; }
install_rejected() { ! k plugin install "$@" >/dev/null; }
reports_finding() { k validate --mr "$TEST_MR" --config "$1" | grep -qF "$FINDING"; }
no_finding() { ! reports_finding "$1"; }
# Disabled rule: the run itself succeeds (so no_finding is not a crash) and prints no finding.
disabled_cleanly() { local out; out="$(k validate --mr "$TEST_MR" --config "$1")" && ! printf '%s' "$out" | grep -qF "$FINDING"; }
no_unknown_key_warning() { ! k validate --mr "$TEST_MR" --config "$1" | grep -q 'E0007'; }

build_plugin() {
    if [ "${SKIP_SDK_PUBLISH:-}" != "1" ] && ! "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" --no-configuration-cache \
        --init-script "$PROJECT_ROOT/tools/ci/extensibility-publish.init.gradle.kts" \
        publishSdkToMavenLocal --console=plain >"$TEMP_DIR/publish.log" 2>&1; then
        tail -20 "$TEMP_DIR/publish.log"
        return 1
    fi
    (cd "$TEMP_DIR" && java -jar "$KARANDASH_JAR" plugin create "$PLUGIN_NAME" --type rule --no-git >"$TEMP_DIR/create.log" 2>&1) &&
        "$PROJECT_ROOT/gradlew" -p "$TEMP_DIR/$PLUGIN_ID" assemble --console=plain >"$TEMP_DIR/build.log" 2>&1 ||
        { tail -20 "$TEMP_DIR/create.log" "$TEMP_DIR/build.log"; return 1; }
}

run_lifecycle() {
    local jar="$TEMP_DIR/$PLUGIN_ID/build/libs/$PLUGIN_ID.jar"
    printf 'rules:\n  %s-check:\n    enabled: true\n    min-title-length: 100\n' "$PLUGIN_NAME" >"$WORK/strict.yaml"
    printf 'rules:\n  %s-check:\n    enabled: true\n    min-title-length: 1\n' "$PLUGIN_NAME" >"$WORK/lenient.yaml"
    printf 'rules:\n  %s-check:\n    enabled: false\n    min-title-length: 100\n' "$PLUGIN_NAME" >"$WORK/disabled.yaml"

    check "unsigned JAR is rejected without --allow-unsigned" install_rejected "$jar"
    check "install from JAR" k plugin install --allow-unsigned "$jar"
    check "installed as $PLUGIN_DIR/$PLUGIN_ID.jar" test -f "$PLUGIN_DIR/$PLUGIN_ID.jar"
    check "plugin list shows $PLUGIN_ID" listed
    check "plugin list shows v0.1.0" listed_version 0.1.0
    check "rule reports a finding on a short title" reports_finding strict.yaml
    check "rule passes when the title is long enough" no_finding lenient.yaml
    check "enabled: false turns the plugin rule off" disabled_cleanly disabled.yaml
    check "enabled is not reported as an unknown rule key" no_unknown_key_warning strict.yaml
    check "duplicate install is rejected" install_rejected --allow-unsigned "$jar"
    check "remove" k plugin remove "$PLUGIN_ID"
    check "plugin list no longer shows $PLUGIN_ID" not_listed
    check "plugin JAR deleted" test ! -f "$PLUGIN_DIR/$PLUGIN_ID.jar"
    check "rule no longer runs after remove" no_finding strict.yaml
    check "re-install after remove" k plugin install --allow-unsigned "$jar"
    check "rule runs again after re-install" reports_finding strict.yaml
}

echo "Karandash plugin lifecycle integration test"
if ! command -v java >/dev/null 2>&1; then
    skip "java not found"
elif [ ! -f "$KARANDASH_JAR" ]; then
    skip "CLI JAR not found (./gradlew :cli:shadowJar)"
elif [ ! -f "$TEST_MR" ]; then
    skip "MR fixture not found: $TEST_MR"
elif ! build_plugin; then
    fail "build the $PLUGIN_ID rule plugin"
else
    run_lifecycle
fi

echo ""
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ]
