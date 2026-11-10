#!/usr/bin/env bash
# verify-plugin-isolation.sh - CI script that enforces cross-tier plugin isolation rules.
#
# Exit code 0 = all checks passed
# Exit code 1 = violations found
#
# Excluded from all checks:
#   plugins/integration-tests/  - allowed to cross tiers
#   plugins/builtin/            - intentionally imports everything
#   plugins/bootstrap/          - bootstrap module
#   plugins/loader/             - loader bridges need cross-tier access
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

VIOLATIONS=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PLUGINS_DIR="$ROOT_DIR/plugins"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

fail() {
    echo -e "${RED}FAIL${NC} $*"
    VIOLATIONS=$((VIOLATIONS + 1))
}

pass() {
    echo -e "${GREEN}PASS${NC} $*"
}

section() {
    echo ""
    echo -e "${YELLOW}==> $*${NC}"
}

# grep_violations <grep-args...>
# Runs grep, prints each matching line as a violation, increments VIOLATIONS.
# Returns 0 always (we handle violations ourselves).
grep_violations() {
    local results
    results=$(grep "$@" 2>/dev/null || true)
    if [[ -n "$results" ]]; then
        while IFS= read -r line; do
            fail "$line"
        done <<< "$results"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Check 1: No plugins-core → plugins-ai imports
# ---------------------------------------------------------------------------

section "Check 1: plugins-core must not import from plugins-ai"

results=$(grep -rn "import io.karandash.plugins.ai" \
    "$PLUGINS_DIR/plugins-core/" \
    2>/dev/null || true)

if [[ -n "$results" ]]; then
    while IFS= read -r line; do
        fail "$line"
    done <<< "$results"
else
    pass "No plugins-core → plugins-ai imports found"
fi

# ---------------------------------------------------------------------------
# Check 2: No plugins-extended → plugins-ai imports
# ---------------------------------------------------------------------------

section "Check 2: plugins-extended must not import from plugins-ai"

results=$(grep -rn "import io.karandash.plugins.ai" \
    "$PLUGINS_DIR/plugins-extended/" \
    2>/dev/null || true)

if [[ -n "$results" ]]; then
    while IFS= read -r line; do
        fail "$line"
    done <<< "$results"
else
    pass "No plugins-extended → plugins-ai imports found"
fi

# ---------------------------------------------------------------------------
# Check 3: No plugins-extended → plugins-core rule class imports (non-test)
# Rule modules in plugins-core use the io.karandash.validators package.
# Test files are exempt (they may use core rule classes in test scenarios).
# ---------------------------------------------------------------------------

section "Check 3: plugins-extended must not import plugins-core rule classes (non-test files)"

# Exclude test source sets: paths containing /test/ or files named *Test.kt.
# Only imports from a package plugins-core declares count: plugins-extended rules share the
# io.karandash.validators root, and a module importing its own sub-package (validators.approval) is fine.
core_validator_pkgs=$(grep -rh --include='*.kt' "^package io.karandash.validators" "$PLUGINS_DIR/plugins-core/" \
    2>/dev/null | sed 's/^package //' | sort -u)
results=$(grep -rn --include='*.kt' --exclude-dir=build "import io.karandash.validators" \
    "$PLUGINS_DIR/plugins-extended/" \
    2>/dev/null \
    | grep -v "/test/" \
    | grep -v "Test\.kt:" \
    | while IFS= read -r line; do
        pkg=$(sed -E 's/.*import (io\.karandash\.validators[A-Za-z0-9_.]*)\.[A-Za-z0-9_*]+.*/\1/' <<< "$line")
        grep -qxF "$pkg" <<< "$core_validator_pkgs" && echo "$line"
    done \
    || true)

if [[ -n "$results" ]]; then
    while IFS= read -r line; do
        fail "$line"
    done <<< "$results"
else
    pass "No plugins-extended → plugins-core rule imports in non-test files"
fi

# ---------------------------------------------------------------------------
# Check 4: No cross-sibling rule module dependencies (build.gradle.kts)
# A rule module must not declare a project() dependency on another rule module
# at the same tier level. Profile modules are exempt (they compose rules).
#
# We scan build.gradle.kts files inside rules/ subdirectories of each tier
# and flag any that reference another rules/ module via project().
# ---------------------------------------------------------------------------

section "Check 4: No cross-sibling rule module build dependencies"

check_cross_sibling_rules() {
    local tier_dir="$1"
    local tier_name="$2"
    local rules_dir="$tier_dir/rules"

    if [[ ! -d "$rules_dir" ]]; then
        pass "No rules/ directory in $tier_name — skipping"
        return
    fi

    # Derive the Gradle path prefix for this tier's rules, e.g.:
    #   plugins/plugins-core  → :plugins:plugins-core:rules:
    local relative_path
    relative_path="${tier_dir#$ROOT_DIR/}"
    local gradle_rules_prefix=":${relative_path//\//:}:rules:"

    local found_violation=false

    while IFS= read -r build_file; do
        # Skip profile modules - they legitimately depend on rule modules
        if [[ "$build_file" == *"/profiles/"* ]]; then
            continue
        fi

        # Find project() references to rules within the same tier
        local rule_deps
        rule_deps=$(grep -n "project(\"${gradle_rules_prefix}" "$build_file" 2>/dev/null || true)

        if [[ -n "$rule_deps" ]]; then
            # Determine the module path relative to rules_dir
            local module_dir
            module_dir="$(dirname "$build_file")"
            local module_rel="${module_dir#$rules_dir/}"

            # It's only a violation if the module itself is inside rules/
            # (i.e. it's a rule depending on another rule, not a profile)
            if [[ "$build_file" == "$rules_dir"* ]]; then
                while IFS= read -r dep_line; do
                    fail "${build_file}:${dep_line}"
                    found_violation=true
                done <<< "$rule_deps"
            fi
        fi
    done < <(find "$tier_dir" -name "build.gradle.kts" 2>/dev/null)

    if [[ "$found_violation" == "false" ]]; then
        pass "No cross-sibling rule dependencies in $tier_name"
    fi
}

check_cross_sibling_rules "$PLUGINS_DIR/plugins-core" "plugins-core"
check_cross_sibling_rules "$PLUGINS_DIR/plugins-extended" "plugins-extended"

# ---------------------------------------------------------------------------
# Check 5: No plugins → cli imports
# ---------------------------------------------------------------------------

section "Check 5: Plugin modules must not import from io.karandash.cli"

results=$(grep -rn "import io.karandash.cli" \
    "$PLUGINS_DIR/plugins-core/" \
    "$PLUGINS_DIR/plugins-extended/" \
    "$PLUGINS_DIR/plugins-ai/" \
    2>/dev/null || true)

if [[ -n "$results" ]]; then
    while IFS= read -r line; do
        fail "$line"
    done <<< "$results"
else
    pass "No plugin → cli imports found"
fi

# ---------------------------------------------------------------------------
# Check 6: No plugins-core/plugins-extended build.gradle.kts depend on plugins-ai
# Excludes: builtin, bootstrap, loader, integration-tests
# ---------------------------------------------------------------------------

section "Check 6: plugins-core and plugins-extended build files must not declare plugins-ai dependencies"

check_no_ai_build_dep() {
    local tier_dir="$1"
    local tier_name="$2"

    local found_violation=false

    while IFS= read -r build_file; do
        # Skip excluded modules
        case "$build_file" in
            *"/integration-tests/"*) continue ;;
            *"/builtin/"*)           continue ;;
            *"/bootstrap/"*)         continue ;;
            *"/loader/"*)            continue ;;
        esac

        local ai_deps
        ai_deps=$(grep -n 'project(":plugins:plugins-ai' "$build_file" 2>/dev/null || true)

        if [[ -n "$ai_deps" ]]; then
            while IFS= read -r dep_line; do
                fail "${build_file}:${dep_line}"
                found_violation=true
            done <<< "$ai_deps"
        fi
    done < <(find "$tier_dir" -name "build.gradle.kts" 2>/dev/null)

    if [[ "$found_violation" == "false" ]]; then
        pass "No plugins-ai build dependencies in $tier_name"
    fi
}

check_no_ai_build_dep "$PLUGINS_DIR/plugins-core" "plugins-core"
check_no_ai_build_dep "$PLUGINS_DIR/plugins-extended" "plugins-extended"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "========================================"
if [[ "$VIOLATIONS" -eq 0 ]]; then
    echo -e "${GREEN}All plugin isolation checks passed${NC}"
    exit 0
else
    echo -e "${RED}${VIOLATIONS} violation(s) found${NC}"
    exit 1
fi
