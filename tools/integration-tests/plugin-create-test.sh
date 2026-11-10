#!/usr/bin/env bash
#
# Integration test: `karandash plugin create` and `karandash generate`.
#
# For every plugin type the scaffold must build, install, load and run:
#   1. `plugin create` and `generate` produce the same project
#   2. the project builds (and its tests pass) against the SDK in mavenLocal
#   3. `plugin install` + `plugin list` in an isolated home
#   4. the module runs: a rule reports its finding, a reporter prints its output,
#      a platform service shows OK in `doctor --plugins`
#
# Env:
#   KARANDASH_JAR     CLI fat JAR (default: cli/build/libs/karandash.jar)
#   TEST_MR           MR fixture (default: tools/integration-tests/fixtures/test-mr.json)
#   PLUGIN_TYPES      types to test (default: all five)
#   SKIP_SDK_PUBLISH  1 = the SDK is already in mavenLocal
#   CI                true = a SKIP counts as a failure
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
KARANDASH_JAR="$(cd "$PROJECT_ROOT" && realpath "${KARANDASH_JAR:-cli/build/libs/karandash.jar}" 2>/dev/null || true)"
TEST_MR="$(cd "$PROJECT_ROOT" && realpath "${TEST_MR:-tools/integration-tests/fixtures/test-mr.json}" 2>/dev/null || true)"
PLUGIN_TYPES="${PLUGIN_TYPES:-rule reporter provider mutator comment-poster}"

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

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

# Camel-cases a kebab-case name with a lowercase first letter: it-rule -> itRule.
lower_camel() {
    local out="" part first=1
    IFS='-' read -ra parts <<< "$1"
    for part in "${parts[@]}"; do
        if [ $first -eq 1 ]; then out="$part"; first=0; else out="$out$(echo "${part:0:1}" | tr '[:lower:]' '[:upper:]')${part:1}"; fi
    done
    echo "$out"
}

# Runs the CLI with an isolated home, so `plugin install` and plugin loading use $1/.karandash/plugins.
karandash_in() {
    local home="$1"
    shift
    HOME="$home" java -Duser.home="$home" -jar "$KARANDASH_JAR" "$@" 2>&1 | grep -v SLF4J
    return "${PIPESTATUS[0]}"
}

test_type() {
    local type="$1"
    local name="it-$type"
    local pkg="io.it.${type//-/}"
    local dir="$TEMP_DIR/$type"
    local project="$dir/create/$name-plugin"
    local home="$dir/home"
    local work="$dir/work"
    mkdir -p "$dir/create" "$dir/generate" "$home" "$work"
    echo ""
    echo "== $type"

    if (cd "$dir/create" && java -jar "$KARANDASH_JAR" plugin create "$name" --type "$type" --package "$pkg" --no-git >"$dir/create.log" 2>&1); then
        pass "$type: plugin create"
    else
        fail "$type: plugin create"; tail -5 "$dir/create.log"; return
    fi
    if java -jar "$KARANDASH_JAR" generate --type "$type" --name "$name" --package "$pkg" --output "$dir/generate" >"$dir/generate.log" 2>&1; then
        pass "$type: generate"
    else
        fail "$type: generate"; tail -5 "$dir/generate.log"
    fi
    if diff -r "$project" "$dir/generate/$name-plugin" >"$dir/diff.log" 2>&1; then
        pass "$type: create and generate produce the same project"
    else
        fail "$type: create and generate differ"; head -20 "$dir/diff.log"
    fi
    if ls "$project/src/commonMain/kotlin/${pkg//.//}/"*Module.kt >/dev/null 2>&1; then
        pass "$type: module source in commonMain"
    else
        fail "$type: no module source under src/commonMain"
    fi

    if "$PROJECT_ROOT/gradlew" -p "$project" build --console=plain >"$dir/build.log" 2>&1; then
        pass "$type: project builds and its tests pass"
    else
        fail "$type: build"; grep -E "^e: |What went wrong" -A3 "$dir/build.log" | head -15; return
    fi
    local jar="$project/build/libs/$name-plugin.jar"
    [ -f "$jar" ] || { fail "$type: $jar not built"; return; }

    if karandash_in "$home" plugin install --allow-unsigned "$jar" >"$dir/install.log"; then
        pass "$type: plugin install"
    else
        fail "$type: plugin install"; tail -5 "$dir/install.log"; return
    fi
    if karandash_in "$home" plugin list | grep -q "$name-plugin"; then
        pass "$type: plugin list shows $name-plugin"
    else
        fail "$type: plugin list does not show $name-plugin"
    fi

    # validate only reads configs under the working directory.
    case "$type" in
        rule)
            printf 'rules:\n  %s-check:\n    enabled: true\n    min-title-length: 100\nreporters:\n  console: true\n' "$name" >"$work/cfg.yaml"
            expect_output "$type" "$work" "  $(lower_camel "$name")Check" validate --mr "$TEST_MR" --config cfg.yaml
            ;;
        reporter)
            printf 'reporters:\n  %s: true\n' "$name" >"$work/cfg.yaml"
            expect_output "$type" "$work" "Result: " validate --mr "$TEST_MR" --config cfg.yaml
            ;;
        *)
            expect_output "$type" "$work" "$name-platform-service" doctor --plugins --offline
            ;;
    esac
}

# expect_output <type> <workdir> <needle> <cli args...> - runs the CLI in <workdir> with the type's home.
expect_output() {
    local type="$1" work="$2" needle="$3"
    shift 3
    local out
    out="$(cd "$work" && karandash_in "$work/../home" "$@")"
    if [ "$type" != "rule" ] && [ "$type" != "reporter" ]; then
        out="$(echo "$out" | grep -F "$needle")"
        needle="OK"
    fi
    if grep -qF -- "$needle" <<<"$out"; then
        pass "$type: generated module runs ($needle)"
    else
        fail "$type: generated module did not run, expected '$needle'"
        echo "$out" | tail -15
    fi
}

echo "Karandash plugin create / generate integration test"
if ! command -v java >/dev/null 2>&1; then
    skip "java not found"
elif [ ! -f "$KARANDASH_JAR" ]; then
    skip "CLI JAR not found (./gradlew :cli:shadowJar)"
elif [ ! -f "$TEST_MR" ]; then
    skip "MR fixture not found: $TEST_MR"
else
    if [ "${SKIP_SDK_PUBLISH:-}" != "1" ]; then
        echo "Publishing the plugin SDK to mavenLocal..."
        if ! "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" --no-configuration-cache \
            --init-script "$PROJECT_ROOT/tools/ci/extensibility-publish.init.gradle.kts" \
            publishSdkToMavenLocal --console=plain >"$TEMP_DIR/publish.log" 2>&1; then
            fail "SDK publish to mavenLocal"; tail -20 "$TEMP_DIR/publish.log"
        fi
    fi
    if [ "$FAIL" -eq 0 ]; then
        for type in $PLUGIN_TYPES; do
            test_type "$type"
        done
    fi
fi

echo ""
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ]
