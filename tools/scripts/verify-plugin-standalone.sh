#!/usr/bin/env bash
set -euo pipefail

# verify-plugin-standalone.sh - Check that a plugin module is ready for standalone publishing.
#
# Verifies that a Gradle module meets all requirements to be published as a standalone
# native shared library (.dylib/.so/.dll) via the Karandash plugin registry.
#
# Checks performed:
#   1. build.gradle.kts applies karandash.kmp-distributable-plugin or
#      karandash.kmp-distributable-ktor-plugin
#   2. build.gradle.kts applies karandash.native-exports (C ABI bridge generation)
#   3. Dependencies are within the allowed set:
#        :plugins:sdk
#        :plugins:helpers
#        :core:*  (any core submodule)
#        :plugins:plugins-ai:ai-core      (AI tier only)
#        :plugins:plugins-ai:providers    (AI tier only)
#        :plugins:plugins-ai:prompts      (AI tier only)
#      External library deps (libs.*) are allowed without restriction.
#      Any dependency on :cli, :dsl, or other plugin tier modules is a FAIL.
#   4. The nativeExports { } block is configured (packageName, moduleClass, moduleType set)
#
# Usage:
#   verify-plugin-standalone.sh <gradle-module-path>
#
# Arguments:
#   <gradle-module-path>   Gradle project path, e.g. :plugins:plugins-core:rules:title-format
#
# Options:
#   --help    Show this help message.
#
# Examples:
#   ./tools/scripts/verify-plugin-standalone.sh :plugins:plugins-extended:outputs:email-output
#   ./tools/scripts/verify-plugin-standalone.sh :plugins:plugins-core:parsers:json
#
# Exit codes:
#   0 - all checks passed
#   1 - one or more checks failed
#   2 - module not found or usage error

# ─── helpers ──────────────────────────────────────────────────────────────────

BOLD='\033[1m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RESET='\033[0m'

pass()  { echo -e "  ${GREEN}PASS${RESET}  $1"; }
fail()  { echo -e "  ${RED}FAIL${RESET}  $1"; FAILED=1; }
info()  { echo -e "  ${CYAN}INFO${RESET}  $1"; }
warn()  { echo -e "  ${YELLOW}WARN${RESET}  $1"; }

usage() {
    sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed 's/^# \{0,2\}//'
    exit 0
}

# ─── argument parsing ─────────────────────────────────────────────────────────

MODULE_PATH=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage ;;
        :*) MODULE_PATH="$1"; shift ;;
        *) echo "ERROR: Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$MODULE_PATH" ]]; then
    echo "ERROR: Module path is required." >&2
    echo "Usage: verify-plugin-standalone.sh <gradle-module-path>" >&2
    exit 2
fi

# ─── locate repo root ─────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ ! -f "$REPO_ROOT/gradlew" ]]; then
    echo "ERROR: gradlew not found at $REPO_ROOT — run from inside the karandash repo." >&2
    exit 2
fi

# ─── resolve module directory ─────────────────────────────────────────────────

MODULE_DIR="$(echo "$MODULE_PATH" | sed 's|^:||; s|:|/|g')"
MODULE_ABS_DIR="$REPO_ROOT/$MODULE_DIR"

if [[ ! -d "$MODULE_ABS_DIR" ]]; then
    echo "ERROR: Module directory not found: $MODULE_ABS_DIR" >&2
    exit 2
fi

BUILD_FILE="$MODULE_ABS_DIR/build.gradle.kts"
if [[ ! -f "$BUILD_FILE" ]]; then
    echo "ERROR: build.gradle.kts not found in $MODULE_ABS_DIR" >&2
    exit 2
fi

# ─── run checks ───────────────────────────────────────────────────────────────

FAILED=0

echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"
echo -e "${BOLD} Karandash Plugin Standalone Readiness Check${RESET}"
echo    " Module: $MODULE_PATH"
echo    " File:   $BUILD_FILE"
echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"
echo ""

# ── Check 1: distributable convention plugin ──────────────────────────────────

echo -e "${BOLD}[1] Distribution convention plugin${RESET}"

if grep -qE 'id\s*\(\s*"karandash\.kmp-distributable-ktor-plugin"\s*\)' "$BUILD_FILE"; then
    pass "Applies karandash.kmp-distributable-ktor-plugin (Ktor variant)"
elif grep -qE 'id\s*\(\s*"karandash\.kmp-distributable-plugin"\s*\)' "$BUILD_FILE"; then
    pass "Applies karandash.kmp-distributable-plugin"
else
    fail "Neither karandash.kmp-distributable-plugin nor karandash.kmp-distributable-ktor-plugin is applied."
    info "Add to build.gradle.kts plugins { } block:"
    info "  id(\"karandash.kmp-distributable-plugin\")       — standard modules"
    info "  id(\"karandash.kmp-distributable-ktor-plugin\")  — HTTP/Ktor modules"
fi
echo ""

# ── Check 2: native-exports convention plugin ─────────────────────────────────

echo -e "${BOLD}[2] Native exports (C ABI bridge)${RESET}"

if grep -qE 'id\s*\(\s*"karandash\.native-exports"\s*\)' "$BUILD_FILE"; then
    pass "Applies karandash.native-exports"
else
    fail "karandash.native-exports is not applied."
    info "Add to build.gradle.kts plugins { } block:"
    info "  id(\"karandash.native-exports\")"
    info "Then configure the nativeExports { } block (see check 4)."
fi
echo ""

# ── Check 3: dependency allowlist ─────────────────────────────────────────────

echo -e "${BOLD}[3] Dependency allowlist${RESET}"

# Extract project() dependency references from build.gradle.kts
# Match: project(":some:path") in any source set dependency block
FORBIDDEN_DEPS=()
ALLOWED_PATTERN='^:(test-fixtures$|plugins:sdk|plugins:helpers|core:|plugins:plugins-ai:ai-core|plugins:plugins-ai:providers|plugins:plugins-ai:prompts)'

while IFS= read -r dep; do
    # strip surrounding quotes and whitespace
    dep_trimmed="$(echo "$dep" | tr -d "\"'" | sed 's/^[[:space:]]*//')"
    if [[ "$dep_trimmed" =~ ^: ]]; then
        if ! echo "$dep_trimmed" | grep -qE "$ALLOWED_PATTERN"; then
            FORBIDDEN_DEPS+=("$dep_trimmed")
        fi
    fi
done < <(grep -oE 'project\([[:space:]]*["'"'"'][^"'"'"']+["'"'"'][[:space:]]*\)' "$BUILD_FILE" 2>/dev/null \
    | grep -oE '["'"'"'][^"'"'"']+["'"'"']' \
    | tr -d "\"'" \
    || true)

if [[ ${#FORBIDDEN_DEPS[@]} -eq 0 ]]; then
    pass "All project() dependencies are within the allowed set."
else
    fail "Forbidden project() dependencies found:"
    for dep in "${FORBIDDEN_DEPS[@]}"; do
        info "    $dep"
    done
    echo ""
    info "Allowed project dependencies for standalone plugins:"
    info "  :plugins:sdk"
    info "  :plugins:helpers"
    info "  :core:*  (any core submodule)"
    info "  :plugins:plugins-ai:ai-core      (AI tier plugins only)"
    info "  :plugins:plugins-ai:providers    (AI tier plugins only)"
    info "  :plugins:plugins-ai:prompts      (AI tier plugins only)"
    info ""
    info "Forbidden: :cli, :dsl, sibling plugin tier modules (:plugins:plugins-core:*, etc.)"
fi
echo ""

# ── Check 4: nativeExports { } block configuration ────────────────────────────

echo -e "${BOLD}[4] nativeExports { } configuration${RESET}"

HAS_NATIVE_EXPORTS_BLOCK=false
HAS_PACKAGE_NAME=false
HAS_MODULE_CLASS=false
HAS_MODULE_TYPE=false

if grep -qE 'nativeExports\s*\{' "$BUILD_FILE"; then
    HAS_NATIVE_EXPORTS_BLOCK=true
fi

if grep -qE 'packageName\s*\.\s*set\s*\(' "$BUILD_FILE"; then
    HAS_PACKAGE_NAME=true
fi

if grep -qE 'moduleClass\s*\.\s*set\s*\(' "$BUILD_FILE"; then
    HAS_MODULE_CLASS=true
fi

if grep -qE 'moduleType\s*\.\s*set\s*\(' "$BUILD_FILE"; then
    HAS_MODULE_TYPE=true
fi

if $HAS_NATIVE_EXPORTS_BLOCK; then
    pass "nativeExports { } block present"
else
    fail "nativeExports { } block is missing"
    info "Add to build.gradle.kts:"
    info "  nativeExports {"
    info "      packageName.set(\"io.karandash.plugins.yourpackage\")"
    info "      moduleClass.set(\"YourModuleClass\")"
    info "      moduleType.set(\"RULE\")  // or REPORTER, OUTPUT, etc."
    info "  }"
fi

if $HAS_PACKAGE_NAME; then
    PACKAGE_NAME="$(grep -oE 'packageName[[:space:]]*\.[[:space:]]*set[[:space:]]*\([[:space:]]*["'"'"'][^"'"'"']+["'"'"']' "$BUILD_FILE" 2>/dev/null \
        | grep -oE '["'"'"'][^"'"'"']+["'"'"']' | tr -d "\"'" | head -1)" || true
    pass "packageName.set(\"${PACKAGE_NAME}\")"
else
    fail "packageName is not set inside nativeExports { }"
fi

if $HAS_MODULE_CLASS; then
    MODULE_CLASS="$(grep -oE 'moduleClass[[:space:]]*\.[[:space:]]*set[[:space:]]*\([[:space:]]*["'"'"'][^"'"'"']+["'"'"']' "$BUILD_FILE" 2>/dev/null \
        | grep -oE '["'"'"'][^"'"'"']+["'"'"']' | tr -d "\"'" | head -1)" || true
    pass "moduleClass.set(\"${MODULE_CLASS}\")"
else
    fail "moduleClass is not set inside nativeExports { }"
fi

if $HAS_MODULE_TYPE; then
    MODULE_TYPE="$(grep -oE 'moduleType[[:space:]]*\.[[:space:]]*set[[:space:]]*\([[:space:]]*["'"'"'][^"'"'"']+["'"'"']' "$BUILD_FILE" 2>/dev/null \
        | grep -oE '["'"'"'][^"'"'"']+["'"'"']' | tr -d "\"'" | head -1)" || true
    pass "moduleType.set(\"${MODULE_TYPE}\")"
else
    fail "moduleType is not set inside nativeExports { }"
fi

echo ""

# ── Summary ───────────────────────────────────────────────────────────────────

echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"
if [[ "$FAILED" -eq 0 ]]; then
    echo -e "${GREEN}${BOLD} READY${RESET} — $MODULE_PATH is configured for standalone publishing."
    echo ""
    echo "  Next step:"
    echo "    ./tools/scripts/publish-plugin.sh $MODULE_PATH"
else
    echo -e "${RED}${BOLD} NOT READY${RESET} — $MODULE_PATH has configuration issues (see above)."
    echo ""
    echo "  Fix all FAIL items, then re-run this script."
fi
echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"
echo ""

exit "$FAILED"
