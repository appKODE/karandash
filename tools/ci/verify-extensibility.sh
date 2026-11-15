#!/usr/bin/env bash
# verify-extensibility.sh - extensibility probe: can a new capability be added as a plugin,
# against the published SDK only, without touching core?
#
#   (a) path guard  - the branch diff (vs ${BASE:-origin/main}) + untracked files may only touch
#                     the probe dir, settings.gradle.kts and plugins/builtin/builtin-modules.txt
#   (b) publish     - :plugins:sdk + its project closure into build/probe-repo (unsigned)
#   (c) compile     - each probe source set independently; PASS/FAIL per probe
#   (d) guest       - JS guest contract test through the real JsPluginLoader
#
# Exit code 0 = every item green
# Exit code 1 = at least one item red (expected today - see tools/examples/extensibility-probe/README.md)
# Exit code 2 = setup error (publish failed or the `baseline` source set does not compile)
#
# Env: BASE (diff base, default origin/main), SKIP_PATH_GUARD=1 to skip (a).
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FAILURES=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROBE_REL="tools/examples/extensibility-probe"
PROBE_DIR="$ROOT_DIR/$PROBE_REL"
INIT_SCRIPT="$SCRIPT_DIR/extensibility-publish.init.gradle.kts"
LOG_DIR="$ROOT_DIR/build/extensibility-probe-logs"
GRADLEW="$ROOT_DIR/gradlew"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

fail() {
    echo -e "${RED}FAIL${NC} $*"
    FAILURES=$((FAILURES + 1))
}

pass() {
    echo -e "${GREEN}PASS${NC} $*"
}

section() {
    echo ""
    echo -e "${YELLOW}==> $*${NC}"
}

setup_error() {
    echo -e "${RED}SETUP ERROR${NC} $*"
    exit 2
}

# is_allowed_path <path> - 0 if the probe may touch it.
is_allowed_path() {
    case "$1" in
        "$PROBE_REL"/*) return 0 ;;
        settings.gradle.kts) return 0 ;;
        plugins/builtin/builtin-modules.txt) return 0 ;;
        *) return 1 ;;
    esac
}

mkdir -p "$LOG_DIR"
cd "$ROOT_DIR"

# ---------------------------------------------------------------------------
# (a) Path guard
# ---------------------------------------------------------------------------
section "(a) Path guard: changes vs ${BASE:-origin/main} stay inside the probe"

if [[ "${SKIP_PATH_GUARD:-0}" == "1" ]]; then
    echo "skipped (SKIP_PATH_GUARD=1)"
else
    changed=$(
        {
            git diff --name-only "${BASE:-origin/main}...HEAD"
            git ls-files --others --exclude-standard
        } | sort -u
    )
    offending=()
    while IFS= read -r path; do
        [[ -z "$path" ]] && continue
        is_allowed_path "$path" || offending+=("$path")
    done <<< "$changed"

    if [[ ${#offending[@]} -eq 0 ]]; then
        pass "path guard — no edits outside $PROBE_REL"
    else
        fail "path guard — ${#offending[@]} path(s) outside the probe:"
        for path in "${offending[@]}"; do
            echo "       $path"
        done
    fi
fi

# ---------------------------------------------------------------------------
# (b) Publish the SDK to the probe repo
# ---------------------------------------------------------------------------
section "(b) Publish SDK to build/probe-repo"

rm -rf "$ROOT_DIR/build/probe-repo"
if "$GRADLEW" --no-configuration-cache --init-script "$INIT_SCRIPT" publishSdkToProbeRepo \
    > "$LOG_DIR/publish.log" 2>&1; then
    pass "SDK published"
else
    tail -n 30 "$LOG_DIR/publish.log"
    setup_error "SDK publish failed — full log: $LOG_DIR/publish.log"
fi

# ---------------------------------------------------------------------------
# (c) Compile each probe item against the published SDK
# ---------------------------------------------------------------------------
section "(c) Compile probe items (standalone build, published SDK only)"

# the probe resolves the SDK at the version just published, not a hardcoded default
KARANDASH_VERSION=$(sed -n 's/^ *version = "\(.*\)"$/\1/p' "$ROOT_DIR/build.gradle.kts" | head -1)

# compile_item <source-set> <label>
compile_item() {
    local source_set="$1" label="$2"
    local task log
    task="compile$(tr '[:lower:]' '[:upper:]' <<< "${source_set:0:1}")${source_set:1}Kotlin"
    log="$LOG_DIR/compile-$source_set.log"
    if "$GRADLEW" -p "$PROBE_DIR" --no-configuration-cache -PkarandashVersion="$KARANDASH_VERSION" "$task" > "$log" 2>&1; then
        pass "$label ($source_set)"
        return 0
    fi
    fail "$label ($source_set) — log: $log"
    grep -E '^e: ' "$log" | head -n 5 | sed 's/^/       /' || true
    return 1
}

if ! compile_item baseline "baseline — existing SDK API"; then
    setup_error "baseline does not compile; the probe setup is broken, M-items not evaluated"
fi
compile_item rule "run context — RunContext.enrichment via validate(..., run)" || true
compile_item analyzer "typed meta keys — MetaKey<T> + PresentationKeys.SECTION" || true
compile_item output "output hints — OutputModule.preferredFormat / supportsFileOutput" || true
compile_item config "module-owned config — top-level probe: block via configSerializer (compile-only)" || true

# ---------------------------------------------------------------------------
# (d) JS guest contract test
# ---------------------------------------------------------------------------
section "(d) JS guest contract test"

if "$GRADLEW" :plugins:integration-tests:contracts:contractTest --tests '*ExtensibilityProbe*' \
    > "$LOG_DIR/guest.log" 2>&1; then
    pass "JS guest declarations forwarded, analyzer-only guest is not a RuleModule"
else
    fail "JS guest contract test — log: $LOG_DIR/guest.log"
    grep -E 'JS guest probe' "$LOG_DIR/guest.log" | head -n 10 | sed 's/^/       /' || true
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
if [[ $FAILURES -eq 0 ]]; then
    echo -e "${GREEN}All extensibility probe items green.${NC}"
    exit 0
fi
echo -e "${RED}$FAILURES extensibility probe item(s) red.${NC}"
exit 1
