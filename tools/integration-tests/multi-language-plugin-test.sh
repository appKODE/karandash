#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="$SCRIPT_DIR/../examples"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Builds below rebuild committed example artifacts (.wasm) in place. On exit, restore every tracked file under
# the examples dir that was clean when the run started, so a run leaves `git status` clean. Rebuild and commit
# those artifacts deliberately with each example's build.sh.
EXAMPLES_DIRTY_AT_START="$(git -C "$EXAMPLES_DIR" diff --name-only --relative -- . 2>/dev/null || true)"
restore_examples() {
    local f changed
    # Collect first: piping `git diff` into `git checkout` races on .git/index.lock.
    changed="$(git -C "$EXAMPLES_DIR" diff --name-only --relative -- . 2>/dev/null || true)"
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        grep -qxF -- "$f" <<< "$EXAMPLES_DIRTY_AT_START" || git -C "$EXAMPLES_DIR" checkout -q -- "$f"
    done <<< "$changed"
}
trap restore_examples EXIT

# Source cargo env if available (for rustup/wasm32 target detection)
[ -f "$HOME/.cargo/env" ] && source "$HOME/.cargo/env" 2>/dev/null || true
PASS=0
FAIL=0
SKIP=0

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Plain arithmetic: ((X++)) returns 1 when X is 0, which ends the script under set -e.
pass() { PASS=$((PASS + 1)); echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { FAIL=$((FAIL + 1)); echo -e "${RED}✗ FAIL${NC}: $1"; }
# In CI a missing toolchain must not pass silently.
skip() {
    if [ "${CI:-}" = "true" ]; then fail "$1 (skipped, but CI=true)"; return; fi
    SKIP=$((SKIP + 1)); echo -e "${YELLOW}⊘ SKIP${NC}: $1"
}

OS=$(uname -s)
ARCH=$(uname -m)

if [ "$OS" = "Darwin" ]; then
    SHARED_EXT="dylib"
    NM_FLAGS="-gU"
else
    SHARED_EXT="so"
    NM_FLAGS="-D"
fi

# ── Symbol sets per module type ───────────────────────────────────────────────

RULE_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_contributed_rules
    karandash_validate
)

REPORTER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_format_id
    karandash_report
)

PROVIDER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_fetch_merge_request
    karandash_fetch_merge_request_by_url
)

MUTATOR_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_add_labels
    karandash_remove_labels
    karandash_set_draft
    karandash_update_title
    karandash_update_description
)

COMMENT_POSTER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_post_comment
    karandash_update_comment
    karandash_find_existing_comment
    karandash_post_inline_comment
)

CLI_COMMAND_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_cli_commands
)

CONFIG_FORMAT_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_config_format_id
    karandash_config_format_extensions
    karandash_config_parse
    karandash_config_parse_extends
)

PROFILE_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_profile_id
    karandash_profile_summary
    karandash_profile_tags
    karandash_profile_rule_configs
    karandash_profile_reporter_configs
)

AI_PROVIDER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_complete
)

AI_INTERCEPTOR_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_intercept
)

AI_ANALYZER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_analyze
)

AI_PROMPT_PROCESSOR_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_process_prompt
    karandash_ai_process_system_prompt
)

AI_RESPONSE_TRANSFORMER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_transform_response
)

AI_CONTEXT_ENRICHER_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_enrich_context
)

AI_REDACTOR_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_redact
)

AI_TOKEN_ALLOCATOR_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
    karandash_ai_allocate_tokens
)

# ── WASM symbol sets (no free_string; uses alloc/dealloc instead) ─────────────

WASM_RULE_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_contributed_rules
    karandash_validate
)

WASM_REPORTER_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_format_id
    karandash_report
)

WASM_CLI_COMMAND_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_cli_commands
)

WASM_CONFIG_FORMAT_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_config_format_id
    karandash_config_format_extensions
    karandash_config_format_parse
    karandash_config_format_parse_extends
)

WASM_PROFILE_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_profile_id
    karandash_profile_summary
    karandash_profile_tags
    karandash_profile_rule_configs
    karandash_profile_reporter_configs
)

WASM_AI_PROVIDER_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_complete
)

WASM_AI_INTERCEPTOR_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_intercept
)

WASM_AI_ANALYZER_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_analyze
)

WASM_AI_PROMPT_PROCESSOR_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_process_prompt
    karandash_ai_process_system_prompt
)

WASM_AI_RESPONSE_TRANSFORMER_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_transform_response
)

WASM_AI_CONTEXT_ENRICHER_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_enrich_context
)

WASM_AI_REDACTOR_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_redact
)

WASM_AI_TOKEN_ALLOCATOR_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_ai_allocate_tokens
)

# ── Helpers ───────────────────────────────────────────────────────────────────

check_symbols() {
    local lib="$1"
    local label="$2"
    shift 2
    local symbols=("$@")
    local missing=0
    local found_syms
    found_syms=$(nm $NM_FLAGS "$lib" 2>/dev/null | grep karandash_ || true)
    for sym in "${symbols[@]}"; do
        if ! grep -q "$sym" <<<"$found_syms"; then
            echo "  Missing symbol: $sym"
            missing=$((missing + 1))
        fi
    done
    if [ $missing -eq 0 ]; then
        pass "$label: symbols"
        return 0
    else
        fail "$label: symbols ($missing missing)"
        return 1
    fi
}

# Check WASM exports via wasm-objdump (wabt) or wasm2wat, fall back to strings
check_wasm_symbols() {
    local wasm="$1"
    local label="$2"
    shift 2
    local symbols=("$@")

    if [ ! -f "$wasm" ]; then
        fail "$label: .wasm file not found ($wasm)"
        return 1
    fi

    local found_syms=""
    if command -v wasm-objdump &>/dev/null; then
        found_syms=$(wasm-objdump -x "$wasm" 2>/dev/null | grep -oE "karandash_[A-Za-z0-9_]+" || true)
    elif command -v wasm2wat &>/dev/null; then
        found_syms=$(wasm2wat "$wasm" 2>/dev/null | grep -oE "karandash_[A-Za-z0-9_]+" || true)
    else
        # Fall back to strings - export names are length-prefixed, and the length byte may be printable
        found_syms=$(strings "$wasm" 2>/dev/null | grep -oE "karandash_[A-Za-z0-9_]+" || true)
    fi

    local missing=0
    for sym in "${symbols[@]}"; do
        if ! grep -qx -- "$sym" <<<"$found_syms"; then
            echo "  Missing export: $sym"
            missing=$((missing + 1))
        fi
    done
    if [ $missing -eq 0 ]; then
        pass "$label: exports"
        return 0
    else
        fail "$label: exports ($missing missing)"
        return 1
    fi
}

check_js_structure() {
    local file="$1"
    local label="$2"
    if [ ! -f "$file" ]; then
        fail "$label: file not found ($file)"
        return 1
    fi
    if grep -q "export default" "$file"; then
        pass "$label: ES module structure"
        return 0
    else
        fail "$label: missing 'export default'"
        return 1
    fi
}

check_jar_classes() {
    local jar="$1"
    local label="$2"
    shift 2
    local classes=("$@")
    local missing=0
    local jar_contents
    jar_contents=$(jar tf "$jar" 2>/dev/null || true)
    for cls in "${classes[@]}"; do
        if ! grep -q "$cls" <<<"$jar_contents"; then
            echo "  Missing class: $cls"
            missing=$((missing + 1))
        fi
    done
    if [ $missing -eq 0 ]; then
        pass "$label: classes"
        return 0
    else
        fail "$label: classes ($missing missing)"
        return 1
    fi
}

echo ""
echo "========================================="
echo "Multi-Language Plugin Integration Tests"
echo "Platform: $OS/$ARCH"
echo "========================================="

# ── C Native Plugins ──────────────────────────────────────────────────────────

echo ""
echo "--- C Native Plugins ---"
if command -v cmake &>/dev/null && command -v make &>/dev/null; then
    BUILD_DIR="$EXAMPLES_DIR/standalone-native-plugin/build-ci"
    mkdir -p "$BUILD_DIR"
    if cmake -S "$EXAMPLES_DIR/standalone-native-plugin" -B "$BUILD_DIR" \
            -DCMAKE_BUILD_TYPE=Release -Wno-dev 2>/dev/null \
        && cmake --build "$BUILD_DIR" 2>/dev/null; then
        RULE_LIB="$BUILD_DIR/libnative-example-rule.$SHARED_EXT"
        REPORTER_LIB="$BUILD_DIR/libnative-example-reporter.$SHARED_EXT"
        PROVIDER_LIB="$BUILD_DIR/libnative-example-provider.$SHARED_EXT"
        MUTATOR_LIB="$BUILD_DIR/libnative-example-mutator.$SHARED_EXT"
        CP_LIB="$BUILD_DIR/libnative-example-comment-poster.$SHARED_EXT"
        [ -f "$RULE_LIB" ]     && check_symbols "$RULE_LIB"     "C rule plugin"           "${RULE_SYMBOLS[@]}"           || { [ -f "$RULE_LIB" ]     || fail "C rule plugin: library not produced"; }
        [ -f "$REPORTER_LIB" ] && check_symbols "$REPORTER_LIB" "C reporter plugin"       "${REPORTER_SYMBOLS[@]}"       || { [ -f "$REPORTER_LIB" ] || fail "C reporter plugin: library not produced"; }
        [ -f "$PROVIDER_LIB" ] && check_symbols "$PROVIDER_LIB" "C provider plugin"       "${PROVIDER_SYMBOLS[@]}"       || { [ -f "$PROVIDER_LIB" ] || fail "C provider plugin: library not produced"; }
        [ -f "$MUTATOR_LIB" ]  && check_symbols "$MUTATOR_LIB"  "C mutator plugin"        "${MUTATOR_SYMBOLS[@]}"        || { [ -f "$MUTATOR_LIB" ]  || fail "C mutator plugin: library not produced"; }
        [ -f "$CP_LIB" ]       && check_symbols "$CP_LIB"       "C comment-poster plugin" "${COMMENT_POSTER_SYMBOLS[@]}" || { [ -f "$CP_LIB" ]       || fail "C comment-poster plugin: library not produced"; }
        CLI_CMD_LIB="$BUILD_DIR/libnative-example-cli-command.$SHARED_EXT"
        CONFIG_FMT_LIB="$BUILD_DIR/libnative-example-config-format.$SHARED_EXT"
        PROFILE_LIB="$BUILD_DIR/libnative-example-profile.$SHARED_EXT"
        AI_PROV_LIB="$BUILD_DIR/libnative-example-ai-provider.$SHARED_EXT"
        AI_INT_LIB="$BUILD_DIR/libnative-example-ai-interceptor.$SHARED_EXT"
        AI_ANA_LIB="$BUILD_DIR/libnative-example-ai-analyzer.$SHARED_EXT"
        AI_PP_LIB="$BUILD_DIR/libnative-example-ai-prompt-processor.$SHARED_EXT"
        AI_RT_LIB="$BUILD_DIR/libnative-example-ai-response-transformer.$SHARED_EXT"
        AI_CE_LIB="$BUILD_DIR/libnative-example-ai-context-enricher.$SHARED_EXT"
        AI_RED_LIB="$BUILD_DIR/libnative-example-ai-redactor.$SHARED_EXT"
        AI_TA_LIB="$BUILD_DIR/libnative-example-ai-token-allocator.$SHARED_EXT"
        [ -f "$CLI_CMD_LIB" ]    && check_symbols "$CLI_CMD_LIB"    "C cli-command plugin"               "${CLI_COMMAND_SYMBOLS[@]}"            || { [ -f "$CLI_CMD_LIB" ]    || fail "C cli-command plugin: library not produced"; }
        [ -f "$CONFIG_FMT_LIB" ] && check_symbols "$CONFIG_FMT_LIB" "C config-format plugin"             "${CONFIG_FORMAT_SYMBOLS[@]}"          || { [ -f "$CONFIG_FMT_LIB" ] || fail "C config-format plugin: library not produced"; }
        [ -f "$PROFILE_LIB" ]    && check_symbols "$PROFILE_LIB"    "C profile plugin"                   "${PROFILE_SYMBOLS[@]}"                || { [ -f "$PROFILE_LIB" ]    || fail "C profile plugin: library not produced"; }
        [ -f "$AI_PROV_LIB" ]    && check_symbols "$AI_PROV_LIB"    "C ai-provider plugin"               "${AI_PROVIDER_SYMBOLS[@]}"            || { [ -f "$AI_PROV_LIB" ]    || fail "C ai-provider plugin: library not produced"; }
        [ -f "$AI_INT_LIB" ]     && check_symbols "$AI_INT_LIB"     "C ai-interceptor plugin"            "${AI_INTERCEPTOR_SYMBOLS[@]}"         || { [ -f "$AI_INT_LIB" ]     || fail "C ai-interceptor plugin: library not produced"; }
        [ -f "$AI_ANA_LIB" ]     && check_symbols "$AI_ANA_LIB"     "C ai-analyzer plugin"               "${AI_ANALYZER_SYMBOLS[@]}"            || { [ -f "$AI_ANA_LIB" ]     || fail "C ai-analyzer plugin: library not produced"; }
        [ -f "$AI_PP_LIB" ]      && check_symbols "$AI_PP_LIB"      "C ai-prompt-processor plugin"       "${AI_PROMPT_PROCESSOR_SYMBOLS[@]}"    || { [ -f "$AI_PP_LIB" ]      || fail "C ai-prompt-processor plugin: library not produced"; }
        [ -f "$AI_RT_LIB" ]      && check_symbols "$AI_RT_LIB"      "C ai-response-transformer plugin"   "${AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || { [ -f "$AI_RT_LIB" ]      || fail "C ai-response-transformer plugin: library not produced"; }
        [ -f "$AI_CE_LIB" ]      && check_symbols "$AI_CE_LIB"      "C ai-context-enricher plugin"       "${AI_CONTEXT_ENRICHER_SYMBOLS[@]}"    || { [ -f "$AI_CE_LIB" ]      || fail "C ai-context-enricher plugin: library not produced"; }
        [ -f "$AI_RED_LIB" ]     && check_symbols "$AI_RED_LIB"     "C ai-redactor plugin"               "${AI_REDACTOR_SYMBOLS[@]}"            || { [ -f "$AI_RED_LIB" ]     || fail "C ai-redactor plugin: library not produced"; }
        [ -f "$AI_TA_LIB" ]      && check_symbols "$AI_TA_LIB"      "C ai-token-allocator plugin"        "${AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"     || { [ -f "$AI_TA_LIB" ]      || fail "C ai-token-allocator plugin: library not produced"; }
    else
        fail "C plugins: build failed"
    fi
else
    skip "C plugins (cmake or make not found)"
fi

# ── C++ Native Plugins ────────────────────────────────────────────────────────

echo ""
echo "--- C++ Native Plugins ---"
if command -v cmake &>/dev/null; then
    BUILD_DIR="$EXAMPLES_DIR/native-plugin-cpp/build-ci"
    mkdir -p "$BUILD_DIR"
    if cmake -S "$EXAMPLES_DIR/native-plugin-cpp" -B "$BUILD_DIR" \
            -DCMAKE_BUILD_TYPE=Release -Wno-dev 2>/dev/null \
        && cmake --build "$BUILD_DIR" 2>/dev/null; then
        RULE_LIB="$BUILD_DIR/libcpp-rule-example.$SHARED_EXT"
        REPORTER_LIB="$BUILD_DIR/libcpp-reporter-example.$SHARED_EXT"
        PROVIDER_LIB="$BUILD_DIR/libcpp-provider-example.$SHARED_EXT"
        MUTATOR_LIB="$BUILD_DIR/libcpp-mutator-example.$SHARED_EXT"
        CP_LIB="$BUILD_DIR/libcpp-comment-poster-example.$SHARED_EXT"
        [ -f "$RULE_LIB" ]     && check_symbols "$RULE_LIB"     "C++ rule plugin"           "${RULE_SYMBOLS[@]}"           || { [ -f "$RULE_LIB" ]     || fail "C++ rule plugin: library not produced"; }
        [ -f "$REPORTER_LIB" ] && check_symbols "$REPORTER_LIB" "C++ reporter plugin"       "${REPORTER_SYMBOLS[@]}"       || { [ -f "$REPORTER_LIB" ] || fail "C++ reporter plugin: library not produced"; }
        [ -f "$PROVIDER_LIB" ] && check_symbols "$PROVIDER_LIB" "C++ provider plugin"       "${PROVIDER_SYMBOLS[@]}"       || { [ -f "$PROVIDER_LIB" ] || fail "C++ provider plugin: library not produced"; }
        [ -f "$MUTATOR_LIB" ]  && check_symbols "$MUTATOR_LIB"  "C++ mutator plugin"        "${MUTATOR_SYMBOLS[@]}"        || { [ -f "$MUTATOR_LIB" ]  || fail "C++ mutator plugin: library not produced"; }
        [ -f "$CP_LIB" ]       && check_symbols "$CP_LIB"       "C++ comment-poster plugin" "${COMMENT_POSTER_SYMBOLS[@]}" || { [ -f "$CP_LIB" ]       || fail "C++ comment-poster plugin: library not produced"; }
        CLI_CMD_LIB="$BUILD_DIR/libcpp-cli-command-example.$SHARED_EXT"
        CONFIG_FMT_LIB="$BUILD_DIR/libcpp-config-format-example.$SHARED_EXT"
        PROFILE_LIB="$BUILD_DIR/libcpp-profile-example.$SHARED_EXT"
        AI_PROV_LIB="$BUILD_DIR/libcpp-ai-provider-example.$SHARED_EXT"
        AI_INT_LIB="$BUILD_DIR/libcpp-ai-interceptor-example.$SHARED_EXT"
        AI_ANA_LIB="$BUILD_DIR/libcpp-ai-analyzer-example.$SHARED_EXT"
        AI_PP_LIB="$BUILD_DIR/libcpp-ai-prompt-processor-example.$SHARED_EXT"
        AI_RT_LIB="$BUILD_DIR/libcpp-ai-response-transformer-example.$SHARED_EXT"
        AI_CE_LIB="$BUILD_DIR/libcpp-ai-context-enricher-example.$SHARED_EXT"
        AI_RED_LIB="$BUILD_DIR/libcpp-ai-redactor-example.$SHARED_EXT"
        AI_TA_LIB="$BUILD_DIR/libcpp-ai-token-allocator-example.$SHARED_EXT"
        [ -f "$CLI_CMD_LIB" ]    && check_symbols "$CLI_CMD_LIB"    "C++ cli-command plugin"               "${CLI_COMMAND_SYMBOLS[@]}"            || { [ -f "$CLI_CMD_LIB" ]    || fail "C++ cli-command plugin: library not produced"; }
        [ -f "$CONFIG_FMT_LIB" ] && check_symbols "$CONFIG_FMT_LIB" "C++ config-format plugin"             "${CONFIG_FORMAT_SYMBOLS[@]}"          || { [ -f "$CONFIG_FMT_LIB" ] || fail "C++ config-format plugin: library not produced"; }
        [ -f "$PROFILE_LIB" ]    && check_symbols "$PROFILE_LIB"    "C++ profile plugin"                   "${PROFILE_SYMBOLS[@]}"                || { [ -f "$PROFILE_LIB" ]    || fail "C++ profile plugin: library not produced"; }
        [ -f "$AI_PROV_LIB" ]    && check_symbols "$AI_PROV_LIB"    "C++ ai-provider plugin"               "${AI_PROVIDER_SYMBOLS[@]}"            || { [ -f "$AI_PROV_LIB" ]    || fail "C++ ai-provider plugin: library not produced"; }
        [ -f "$AI_INT_LIB" ]     && check_symbols "$AI_INT_LIB"     "C++ ai-interceptor plugin"            "${AI_INTERCEPTOR_SYMBOLS[@]}"         || { [ -f "$AI_INT_LIB" ]     || fail "C++ ai-interceptor plugin: library not produced"; }
        [ -f "$AI_ANA_LIB" ]     && check_symbols "$AI_ANA_LIB"     "C++ ai-analyzer plugin"               "${AI_ANALYZER_SYMBOLS[@]}"            || { [ -f "$AI_ANA_LIB" ]     || fail "C++ ai-analyzer plugin: library not produced"; }
        [ -f "$AI_PP_LIB" ]      && check_symbols "$AI_PP_LIB"      "C++ ai-prompt-processor plugin"       "${AI_PROMPT_PROCESSOR_SYMBOLS[@]}"    || { [ -f "$AI_PP_LIB" ]      || fail "C++ ai-prompt-processor plugin: library not produced"; }
        [ -f "$AI_RT_LIB" ]      && check_symbols "$AI_RT_LIB"      "C++ ai-response-transformer plugin"   "${AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || { [ -f "$AI_RT_LIB" ]      || fail "C++ ai-response-transformer plugin: library not produced"; }
        [ -f "$AI_CE_LIB" ]      && check_symbols "$AI_CE_LIB"      "C++ ai-context-enricher plugin"       "${AI_CONTEXT_ENRICHER_SYMBOLS[@]}"    || { [ -f "$AI_CE_LIB" ]      || fail "C++ ai-context-enricher plugin: library not produced"; }
        [ -f "$AI_RED_LIB" ]     && check_symbols "$AI_RED_LIB"     "C++ ai-redactor plugin"               "${AI_REDACTOR_SYMBOLS[@]}"            || { [ -f "$AI_RED_LIB" ]     || fail "C++ ai-redactor plugin: library not produced"; }
        [ -f "$AI_TA_LIB" ]      && check_symbols "$AI_TA_LIB"      "C++ ai-token-allocator plugin"        "${AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"     || { [ -f "$AI_TA_LIB" ]      || fail "C++ ai-token-allocator plugin: library not produced"; }
    else
        fail "C++ plugins: build failed"
    fi
else
    skip "C++ plugins (cmake not found)"
fi

# ── Rust Native Plugins ───────────────────────────────────────────────────────

echo ""
echo "--- Rust Native Plugins ---"
if command -v cargo &>/dev/null; then
    RUST_DIR="$EXAMPLES_DIR/native-plugin-rust"
    if (cd "$RUST_DIR" && cargo build --release 2>/dev/null); then
        TARGET_DIR="$RUST_DIR/target/release"
        RULE_LIB="$TARGET_DIR/libkarandash_rust_rule_reporter.$SHARED_EXT"
        PROVIDER_LIB="$TARGET_DIR/libkarandash_rust_provider.$SHARED_EXT"
        MUTATOR_LIB="$TARGET_DIR/libkarandash_rust_mutator.$SHARED_EXT"
        CP_LIB="$TARGET_DIR/libkarandash_rust_comment_poster.$SHARED_EXT"
        # rule-reporter lib exports both RULE and REPORTER symbols
        [ -f "$RULE_LIB" ]     && check_symbols "$RULE_LIB"     "Rust rule+reporter plugin" "${RULE_SYMBOLS[@]}"           || { [ -f "$RULE_LIB" ]     || fail "Rust rule+reporter plugin: library not produced"; }
        [ -f "$PROVIDER_LIB" ] && check_symbols "$PROVIDER_LIB" "Rust provider plugin"      "${PROVIDER_SYMBOLS[@]}"       || { [ -f "$PROVIDER_LIB" ] || fail "Rust provider plugin: library not produced"; }
        [ -f "$MUTATOR_LIB" ]  && check_symbols "$MUTATOR_LIB"  "Rust mutator plugin"       "${MUTATOR_SYMBOLS[@]}"        || { [ -f "$MUTATOR_LIB" ]  || fail "Rust mutator plugin: library not produced"; }
        [ -f "$CP_LIB" ]       && check_symbols "$CP_LIB"       "Rust comment-poster plugin" "${COMMENT_POSTER_SYMBOLS[@]}" || { [ -f "$CP_LIB" ]       || fail "Rust comment-poster plugin: library not produced"; }
        CLI_CMD_LIB="$TARGET_DIR/libkarandash_rust_cli_command.$SHARED_EXT"
        CONFIG_FMT_LIB="$TARGET_DIR/libkarandash_rust_config_format.$SHARED_EXT"
        PROFILE_LIB="$TARGET_DIR/libkarandash_rust_profile.$SHARED_EXT"
        AI_PROV_LIB="$TARGET_DIR/libkarandash_rust_ai_provider.$SHARED_EXT"
        AI_INT_LIB="$TARGET_DIR/libkarandash_rust_ai_interceptor.$SHARED_EXT"
        AI_ANA_LIB="$TARGET_DIR/libkarandash_rust_ai_analyzer.$SHARED_EXT"
        AI_PP_LIB="$TARGET_DIR/libkarandash_rust_ai_prompt_processor.$SHARED_EXT"
        AI_RT_LIB="$TARGET_DIR/libkarandash_rust_ai_response_transformer.$SHARED_EXT"
        AI_CE_LIB="$TARGET_DIR/libkarandash_rust_ai_context_enricher.$SHARED_EXT"
        AI_RED_LIB="$TARGET_DIR/libkarandash_rust_ai_redactor.$SHARED_EXT"
        AI_TA_LIB="$TARGET_DIR/libkarandash_rust_ai_token_allocator.$SHARED_EXT"
        [ -f "$CLI_CMD_LIB" ]    && check_symbols "$CLI_CMD_LIB"    "Rust cli-command plugin"               "${CLI_COMMAND_SYMBOLS[@]}"            || { [ -f "$CLI_CMD_LIB" ]    || fail "Rust cli-command plugin: library not produced"; }
        [ -f "$CONFIG_FMT_LIB" ] && check_symbols "$CONFIG_FMT_LIB" "Rust config-format plugin"             "${CONFIG_FORMAT_SYMBOLS[@]}"          || { [ -f "$CONFIG_FMT_LIB" ] || fail "Rust config-format plugin: library not produced"; }
        [ -f "$PROFILE_LIB" ]    && check_symbols "$PROFILE_LIB"    "Rust profile plugin"                   "${PROFILE_SYMBOLS[@]}"                || { [ -f "$PROFILE_LIB" ]    || fail "Rust profile plugin: library not produced"; }
        [ -f "$AI_PROV_LIB" ]    && check_symbols "$AI_PROV_LIB"    "Rust ai-provider plugin"               "${AI_PROVIDER_SYMBOLS[@]}"            || { [ -f "$AI_PROV_LIB" ]    || fail "Rust ai-provider plugin: library not produced"; }
        [ -f "$AI_INT_LIB" ]     && check_symbols "$AI_INT_LIB"     "Rust ai-interceptor plugin"            "${AI_INTERCEPTOR_SYMBOLS[@]}"         || { [ -f "$AI_INT_LIB" ]     || fail "Rust ai-interceptor plugin: library not produced"; }
        [ -f "$AI_ANA_LIB" ]     && check_symbols "$AI_ANA_LIB"     "Rust ai-analyzer plugin"               "${AI_ANALYZER_SYMBOLS[@]}"            || { [ -f "$AI_ANA_LIB" ]     || fail "Rust ai-analyzer plugin: library not produced"; }
        [ -f "$AI_PP_LIB" ]      && check_symbols "$AI_PP_LIB"      "Rust ai-prompt-processor plugin"       "${AI_PROMPT_PROCESSOR_SYMBOLS[@]}"    || { [ -f "$AI_PP_LIB" ]      || fail "Rust ai-prompt-processor plugin: library not produced"; }
        [ -f "$AI_RT_LIB" ]      && check_symbols "$AI_RT_LIB"      "Rust ai-response-transformer plugin"   "${AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || { [ -f "$AI_RT_LIB" ]      || fail "Rust ai-response-transformer plugin: library not produced"; }
        [ -f "$AI_CE_LIB" ]      && check_symbols "$AI_CE_LIB"      "Rust ai-context-enricher plugin"       "${AI_CONTEXT_ENRICHER_SYMBOLS[@]}"    || { [ -f "$AI_CE_LIB" ]      || fail "Rust ai-context-enricher plugin: library not produced"; }
        [ -f "$AI_RED_LIB" ]     && check_symbols "$AI_RED_LIB"     "Rust ai-redactor plugin"               "${AI_REDACTOR_SYMBOLS[@]}"            || { [ -f "$AI_RED_LIB" ]     || fail "Rust ai-redactor plugin: library not produced"; }
        [ -f "$AI_TA_LIB" ]      && check_symbols "$AI_TA_LIB"      "Rust ai-token-allocator plugin"        "${AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"     || { [ -f "$AI_TA_LIB" ]      || fail "Rust ai-token-allocator plugin: library not produced"; }
    else
        fail "Rust native plugins: build failed"
    fi
else
    skip "Rust native plugins (cargo not found)"
fi

# ── Go Native Plugins ─────────────────────────────────────────────────────────

echo ""
echo "--- Go Native Plugins ---"
if command -v go &>/dev/null; then
    GO_DIR="$EXAMPLES_DIR/native-plugin-go"
    if [ "$OS" = "Darwin" ]; then
        BUILD_TARGET="macos-arm64"
    else
        BUILD_TARGET="linux-x64"
    fi
    if (cd "$GO_DIR" && make "$BUILD_TARGET" 2>/dev/null); then
        RULE_LIB="$GO_DIR/libgo-example.$SHARED_EXT"
        PROVIDER_LIB="$GO_DIR/provider/libgo-example-provider.$SHARED_EXT"
        MUTATOR_LIB="$GO_DIR/mutator/libgo-example-mutator.$SHARED_EXT"
        CP_LIB="$GO_DIR/comment_poster/libgo-example-comment-poster.$SHARED_EXT"
        [ -f "$RULE_LIB" ]     && check_symbols "$RULE_LIB"     "Go rule+reporter plugin"   "${RULE_SYMBOLS[@]}"           || { [ -f "$RULE_LIB" ]     || fail "Go rule+reporter plugin: library not produced"; }
        [ -f "$PROVIDER_LIB" ] && check_symbols "$PROVIDER_LIB" "Go provider plugin"        "${PROVIDER_SYMBOLS[@]}"       || { [ -f "$PROVIDER_LIB" ] || fail "Go provider plugin: library not produced"; }
        [ -f "$MUTATOR_LIB" ]  && check_symbols "$MUTATOR_LIB"  "Go mutator plugin"         "${MUTATOR_SYMBOLS[@]}"        || { [ -f "$MUTATOR_LIB" ]  || fail "Go mutator plugin: library not produced"; }
        [ -f "$CP_LIB" ]       && check_symbols "$CP_LIB"       "Go comment-poster plugin"  "${COMMENT_POSTER_SYMBOLS[@]}" || { [ -f "$CP_LIB" ]       || fail "Go comment-poster plugin: library not produced"; }
        CLI_CMD_LIB="$GO_DIR/cli_command/libgo-example-cli-command.$SHARED_EXT"
        CONFIG_FMT_LIB="$GO_DIR/config_format/libgo-example-config-format.$SHARED_EXT"
        PROFILE_LIB="$GO_DIR/profile/libgo-example-profile.$SHARED_EXT"
        AI_PROV_LIB="$GO_DIR/ai_provider/libgo-example-ai-provider.$SHARED_EXT"
        AI_INT_LIB="$GO_DIR/ai_interceptor/libgo-example-ai-interceptor.$SHARED_EXT"
        AI_ANA_LIB="$GO_DIR/ai_analyzer/libgo-example-ai-analyzer.$SHARED_EXT"
        AI_PP_LIB="$GO_DIR/ai_prompt_processor/libgo-example-ai-prompt-processor.$SHARED_EXT"
        AI_RT_LIB="$GO_DIR/ai_response_transformer/libgo-example-ai-response-transformer.$SHARED_EXT"
        AI_CE_LIB="$GO_DIR/ai_context_enricher/libgo-example-ai-context-enricher.$SHARED_EXT"
        AI_RED_LIB="$GO_DIR/ai_redactor/libgo-example-ai-redactor.$SHARED_EXT"
        AI_TA_LIB="$GO_DIR/ai_token_allocator/libgo-example-ai-token-allocator.$SHARED_EXT"
        [ -f "$CLI_CMD_LIB" ]    && check_symbols "$CLI_CMD_LIB"    "Go cli-command plugin"               "${CLI_COMMAND_SYMBOLS[@]}"            || { [ -f "$CLI_CMD_LIB" ]    || fail "Go cli-command plugin: library not produced"; }
        [ -f "$CONFIG_FMT_LIB" ] && check_symbols "$CONFIG_FMT_LIB" "Go config-format plugin"             "${CONFIG_FORMAT_SYMBOLS[@]}"          || { [ -f "$CONFIG_FMT_LIB" ] || fail "Go config-format plugin: library not produced"; }
        [ -f "$PROFILE_LIB" ]    && check_symbols "$PROFILE_LIB"    "Go profile plugin"                   "${PROFILE_SYMBOLS[@]}"                || { [ -f "$PROFILE_LIB" ]    || fail "Go profile plugin: library not produced"; }
        [ -f "$AI_PROV_LIB" ]    && check_symbols "$AI_PROV_LIB"    "Go ai-provider plugin"               "${AI_PROVIDER_SYMBOLS[@]}"            || { [ -f "$AI_PROV_LIB" ]    || fail "Go ai-provider plugin: library not produced"; }
        [ -f "$AI_INT_LIB" ]     && check_symbols "$AI_INT_LIB"     "Go ai-interceptor plugin"            "${AI_INTERCEPTOR_SYMBOLS[@]}"         || { [ -f "$AI_INT_LIB" ]     || fail "Go ai-interceptor plugin: library not produced"; }
        [ -f "$AI_ANA_LIB" ]     && check_symbols "$AI_ANA_LIB"     "Go ai-analyzer plugin"               "${AI_ANALYZER_SYMBOLS[@]}"            || { [ -f "$AI_ANA_LIB" ]     || fail "Go ai-analyzer plugin: library not produced"; }
        [ -f "$AI_PP_LIB" ]      && check_symbols "$AI_PP_LIB"      "Go ai-prompt-processor plugin"       "${AI_PROMPT_PROCESSOR_SYMBOLS[@]}"    || { [ -f "$AI_PP_LIB" ]      || fail "Go ai-prompt-processor plugin: library not produced"; }
        [ -f "$AI_RT_LIB" ]      && check_symbols "$AI_RT_LIB"      "Go ai-response-transformer plugin"   "${AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || { [ -f "$AI_RT_LIB" ]      || fail "Go ai-response-transformer plugin: library not produced"; }
        [ -f "$AI_CE_LIB" ]      && check_symbols "$AI_CE_LIB"      "Go ai-context-enricher plugin"       "${AI_CONTEXT_ENRICHER_SYMBOLS[@]}"    || { [ -f "$AI_CE_LIB" ]      || fail "Go ai-context-enricher plugin: library not produced"; }
        [ -f "$AI_RED_LIB" ]     && check_symbols "$AI_RED_LIB"     "Go ai-redactor plugin"               "${AI_REDACTOR_SYMBOLS[@]}"            || { [ -f "$AI_RED_LIB" ]     || fail "Go ai-redactor plugin: library not produced"; }
        [ -f "$AI_TA_LIB" ]      && check_symbols "$AI_TA_LIB"      "Go ai-token-allocator plugin"        "${AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"     || { [ -f "$AI_TA_LIB" ]      || fail "Go ai-token-allocator plugin: library not produced"; }
    else
        fail "Go plugins: build failed"
    fi
else
    skip "Go plugins (go not found)"
fi

# ── Kotlin/Native Plugin ──────────────────────────────────────────────────────

echo ""
echo "--- Kotlin/Native Plugin ---"
# The example declares macosArm64 and linuxX64 only
KOTLIN_TARGET=""
if [ "$OS" = "Darwin" ] && [ "$ARCH" = "arm64" ]; then
    KOTLIN_TARGET=MacosArm64 KOTLIN_LIB="$EXAMPLES_DIR/native-plugin-kotlin/build/bin/macosArm64/releaseShared/libkotlin_example.dylib"
elif [ "$OS" = "Linux" ] && [ "$ARCH" = "x86_64" ]; then
    KOTLIN_TARGET=LinuxX64 KOTLIN_LIB="$EXAMPLES_DIR/native-plugin-kotlin/build/bin/linuxX64/releaseShared/libkotlin_example.so"
fi
if [ -n "$KOTLIN_TARGET" ]; then
    if (cd "$EXAMPLES_DIR/native-plugin-kotlin" \
        && ./gradlew "linkReleaseShared$KOTLIN_TARGET" --quiet --no-daemon 2>/dev/null); then
        if [ -f "$KOTLIN_LIB" ]; then
            # The Kotlin/Native lib bundles all module types in one dylib
            ALL_KOTLIN_SYMBOLS=(
                "${RULE_SYMBOLS[@]}"
                karandash_format_id karandash_report
                karandash_fetch_merge_request karandash_fetch_merge_request_by_url
                karandash_add_labels karandash_remove_labels karandash_set_draft karandash_update_title karandash_update_description
                karandash_post_comment karandash_update_comment
                karandash_find_existing_comment karandash_post_inline_comment
                karandash_cli_commands
                karandash_config_format_id karandash_config_format_extensions
                karandash_config_parse karandash_config_parse_extends
                karandash_profile_id karandash_profile_summary karandash_profile_tags
                karandash_profile_rule_configs karandash_profile_reporter_configs
                karandash_ai_complete
                karandash_ai_intercept
                karandash_ai_analyze
                karandash_ai_process_prompt karandash_ai_process_system_prompt
                karandash_ai_transform_response
                karandash_ai_enrich_context
                karandash_ai_redact
                karandash_ai_allocate_tokens
            )
            check_symbols "$KOTLIN_LIB" "Kotlin/Native plugin (all types)" "${ALL_KOTLIN_SYMBOLS[@]}" || true
        else
            fail "Kotlin/Native plugin: library not produced"
        fi
    else
        fail "Kotlin/Native plugin: build failed"
    fi
else
    skip "Kotlin/Native plugin (example targets macOS ARM64 and Linux x64 only)"
fi

# ── JavaScript Plugins ────────────────────────────────────────────────────────

echo ""
echo "--- JavaScript Plugins ---"
check_js_structure "$EXAMPLES_DIR/standalone-js-plugin/example-rule.mjs"                        "JS standalone rule"                   || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/rule-plugin.mjs"                           "JS rule plugin"                       || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/reporter-plugin.mjs"                       "JS reporter plugin"                   || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/provider-plugin.mjs"                       "JS provider plugin"                   || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/comment-poster-plugin.mjs"                 "JS comment-poster plugin"             || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/mutator-plugin.mjs"                        "JS mutator plugin"                    || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/cli-command-plugin.mjs"                    "JS cli-command plugin"                || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/config-format-plugin.mjs"                  "JS config-format plugin"              || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/profile-plugin.mjs"                        "JS profile plugin"                    || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-provider-plugin.mjs"                    "JS ai-provider plugin"                || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-interceptor-plugin.mjs"                 "JS ai-interceptor plugin"             || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-analyzer-plugin.mjs"                    "JS ai-analyzer plugin"                || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-prompt-processor-plugin.mjs"            "JS ai-prompt-processor plugin"        || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-response-transformer-plugin.mjs"        "JS ai-response-transformer plugin"    || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-context-enricher-plugin.mjs"            "JS ai-context-enricher plugin"        || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-redactor-plugin.mjs"                    "JS ai-redactor plugin"                || true
check_js_structure "$EXAMPLES_DIR/js-plugins-all-types/ai-token-allocator-plugin.mjs"             "JS ai-token-allocator plugin"         || true

# ── Python-Generated Native Plugins ──────────────────────────────────────────

echo ""
echo "--- Python-Generated Native Plugins ---"
if command -v python3 &>/dev/null && command -v cc &>/dev/null; then
    PYTHON_DIR="$EXAMPLES_DIR/native-plugin-python-c-generator"
    if (cd "$PYTHON_DIR" && python3 generate_plugin.py 2>/dev/null); then
        RULE_LIB="$PYTHON_DIR/libpython-rule.$SHARED_EXT"
        REPORTER_LIB="$PYTHON_DIR/libpython-reporter.$SHARED_EXT"
        PROVIDER_LIB="$PYTHON_DIR/libpython-provider.$SHARED_EXT"
        MUTATOR_LIB="$PYTHON_DIR/libpython-mutator.$SHARED_EXT"
        CP_LIB="$PYTHON_DIR/libpython-comment-poster.$SHARED_EXT"
        [ -f "$RULE_LIB" ]     && check_symbols "$RULE_LIB"     "Python rule plugin"           "${RULE_SYMBOLS[@]}"           || { [ -f "$RULE_LIB" ]     || fail "Python rule plugin: library not produced"; }
        [ -f "$REPORTER_LIB" ] && check_symbols "$REPORTER_LIB" "Python reporter plugin"       "${REPORTER_SYMBOLS[@]}"       || { [ -f "$REPORTER_LIB" ] || fail "Python reporter plugin: library not produced"; }
        [ -f "$PROVIDER_LIB" ] && check_symbols "$PROVIDER_LIB" "Python provider plugin"       "${PROVIDER_SYMBOLS[@]}"       || { [ -f "$PROVIDER_LIB" ] || fail "Python provider plugin: library not produced"; }
        [ -f "$MUTATOR_LIB" ]  && check_symbols "$MUTATOR_LIB"  "Python mutator plugin"        "${MUTATOR_SYMBOLS[@]}"        || { [ -f "$MUTATOR_LIB" ]  || fail "Python mutator plugin: library not produced"; }
        [ -f "$CP_LIB" ]       && check_symbols "$CP_LIB"       "Python comment-poster plugin" "${COMMENT_POSTER_SYMBOLS[@]}" || { [ -f "$CP_LIB" ]       || fail "Python comment-poster plugin: library not produced"; }
        CLI_CMD_LIB="$PYTHON_DIR/libpython-cli-command.$SHARED_EXT"
        CONFIG_FMT_LIB="$PYTHON_DIR/libpython-config-format.$SHARED_EXT"
        PROFILE_LIB="$PYTHON_DIR/libpython-profile.$SHARED_EXT"
        AI_PROV_LIB="$PYTHON_DIR/libpython-ai-provider.$SHARED_EXT"
        AI_INT_LIB="$PYTHON_DIR/libpython-ai-interceptor.$SHARED_EXT"
        AI_ANA_LIB="$PYTHON_DIR/libpython-ai-analyzer.$SHARED_EXT"
        AI_PP_LIB="$PYTHON_DIR/libpython-ai-prompt-processor.$SHARED_EXT"
        AI_RT_LIB="$PYTHON_DIR/libpython-ai-response-transformer.$SHARED_EXT"
        AI_CE_LIB="$PYTHON_DIR/libpython-ai-context-enricher.$SHARED_EXT"
        AI_RED_LIB="$PYTHON_DIR/libpython-ai-redactor.$SHARED_EXT"
        AI_TA_LIB="$PYTHON_DIR/libpython-ai-token-allocator.$SHARED_EXT"
        [ -f "$CLI_CMD_LIB" ]    && check_symbols "$CLI_CMD_LIB"    "Python cli-command plugin"               "${CLI_COMMAND_SYMBOLS[@]}"            || { [ -f "$CLI_CMD_LIB" ]    || fail "Python cli-command plugin: library not produced"; }
        [ -f "$CONFIG_FMT_LIB" ] && check_symbols "$CONFIG_FMT_LIB" "Python config-format plugin"             "${CONFIG_FORMAT_SYMBOLS[@]}"          || { [ -f "$CONFIG_FMT_LIB" ] || fail "Python config-format plugin: library not produced"; }
        [ -f "$PROFILE_LIB" ]    && check_symbols "$PROFILE_LIB"    "Python profile plugin"                   "${PROFILE_SYMBOLS[@]}"                || { [ -f "$PROFILE_LIB" ]    || fail "Python profile plugin: library not produced"; }
        [ -f "$AI_PROV_LIB" ]    && check_symbols "$AI_PROV_LIB"    "Python ai-provider plugin"               "${AI_PROVIDER_SYMBOLS[@]}"            || { [ -f "$AI_PROV_LIB" ]    || fail "Python ai-provider plugin: library not produced"; }
        [ -f "$AI_INT_LIB" ]     && check_symbols "$AI_INT_LIB"     "Python ai-interceptor plugin"            "${AI_INTERCEPTOR_SYMBOLS[@]}"         || { [ -f "$AI_INT_LIB" ]     || fail "Python ai-interceptor plugin: library not produced"; }
        [ -f "$AI_ANA_LIB" ]     && check_symbols "$AI_ANA_LIB"     "Python ai-analyzer plugin"               "${AI_ANALYZER_SYMBOLS[@]}"            || { [ -f "$AI_ANA_LIB" ]     || fail "Python ai-analyzer plugin: library not produced"; }
        [ -f "$AI_PP_LIB" ]      && check_symbols "$AI_PP_LIB"      "Python ai-prompt-processor plugin"       "${AI_PROMPT_PROCESSOR_SYMBOLS[@]}"    || { [ -f "$AI_PP_LIB" ]      || fail "Python ai-prompt-processor plugin: library not produced"; }
        [ -f "$AI_RT_LIB" ]      && check_symbols "$AI_RT_LIB"      "Python ai-response-transformer plugin"   "${AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || { [ -f "$AI_RT_LIB" ]      || fail "Python ai-response-transformer plugin: library not produced"; }
        [ -f "$AI_CE_LIB" ]      && check_symbols "$AI_CE_LIB"      "Python ai-context-enricher plugin"       "${AI_CONTEXT_ENRICHER_SYMBOLS[@]}"    || { [ -f "$AI_CE_LIB" ]      || fail "Python ai-context-enricher plugin: library not produced"; }
        [ -f "$AI_RED_LIB" ]     && check_symbols "$AI_RED_LIB"     "Python ai-redactor plugin"               "${AI_REDACTOR_SYMBOLS[@]}"            || { [ -f "$AI_RED_LIB" ]     || fail "Python ai-redactor plugin: library not produced"; }
        [ -f "$AI_TA_LIB" ]      && check_symbols "$AI_TA_LIB"      "Python ai-token-allocator plugin"        "${AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"     || { [ -f "$AI_TA_LIB" ]      || fail "Python ai-token-allocator plugin: library not produced"; }
    else
        fail "Python-generated plugins: build failed"
    fi
else
    skip "Python-generated plugins (python3 or cc not found)"
fi

# ── WASM Plugin (C) ───────────────────────────────────────────────────────────

echo ""
echo "--- WASM Plugin (C) ---"
if command -v emcc &>/dev/null || (command -v clang &>/dev/null && clang --print-targets 2>&1 | grep -q wasm); then
    WASM_C_DIR="$EXAMPLES_DIR/wasm-plugin-c"
    if "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :plugins:loader:buildExampleWasm-wasm-plugin-c --quiet >/dev/null 2>&1; then
        check_wasm_symbols "$WASM_C_DIR/plugin.wasm"                        "WASM C rule plugin"                     "${WASM_RULE_SYMBOLS[@]}"                     || true
        check_wasm_symbols "$WASM_C_DIR/reporter-plugin.wasm"               "WASM C reporter plugin"                 "${WASM_REPORTER_SYMBOLS[@]}"                 || true
        check_wasm_symbols "$WASM_C_DIR/cli-command-plugin.wasm"            "WASM C cli-command plugin"              "${WASM_CLI_COMMAND_SYMBOLS[@]}"              || true
        check_wasm_symbols "$WASM_C_DIR/config-format-plugin.wasm"          "WASM C config-format plugin"            "${WASM_CONFIG_FORMAT_SYMBOLS[@]}"            || true
        check_wasm_symbols "$WASM_C_DIR/profile-plugin.wasm"                "WASM C profile plugin"                  "${WASM_PROFILE_SYMBOLS[@]}"                  || true
        check_wasm_symbols "$WASM_C_DIR/ai-provider-plugin.wasm"            "WASM C ai-provider plugin"              "${WASM_AI_PROVIDER_SYMBOLS[@]}"              || true
        check_wasm_symbols "$WASM_C_DIR/ai-interceptor-plugin.wasm"         "WASM C ai-interceptor plugin"           "${WASM_AI_INTERCEPTOR_SYMBOLS[@]}"           || true
        check_wasm_symbols "$WASM_C_DIR/ai-analyzer-plugin.wasm"            "WASM C ai-analyzer plugin"              "${WASM_AI_ANALYZER_SYMBOLS[@]}"              || true
        check_wasm_symbols "$WASM_C_DIR/ai-prompt-processor-plugin.wasm"    "WASM C ai-prompt-processor plugin"      "${WASM_AI_PROMPT_PROCESSOR_SYMBOLS[@]}"      || true
        check_wasm_symbols "$WASM_C_DIR/ai-response-transformer-plugin.wasm" "WASM C ai-response-transformer plugin" "${WASM_AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}"  || true
        check_wasm_symbols "$WASM_C_DIR/ai-context-enricher-plugin.wasm"    "WASM C ai-context-enricher plugin"      "${WASM_AI_CONTEXT_ENRICHER_SYMBOLS[@]}"      || true
        check_wasm_symbols "$WASM_C_DIR/ai-redactor-plugin.wasm"            "WASM C ai-redactor plugin"              "${WASM_AI_REDACTOR_SYMBOLS[@]}"              || true
        check_wasm_symbols "$WASM_C_DIR/ai-token-allocator-plugin.wasm"     "WASM C ai-token-allocator plugin"       "${WASM_AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"       || true
    else
        fail "WASM C plugins: build failed"
    fi
else
    skip "WASM C plugins (emcc or clang with wasm32 not available)"
fi

# ── WASM Plugin (Rust) ────────────────────────────────────────────────────────

echo ""
echo "--- WASM Plugin (Rust) ---"
if command -v cargo &>/dev/null && rustup target list --installed 2>/dev/null | grep -q "wasm32-unknown-unknown"; then
    WASM_RUST_DIR="$EXAMPLES_DIR/wasm-plugin-rust"
    if "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :plugins:loader:buildExampleWasm-wasm-plugin-rust --quiet >/dev/null 2>&1; then
        # the Gradle task (build.sh) emits one karandash_wasm_<type>.wasm per module type
        wr() { echo "$WASM_RUST_DIR/karandash_wasm_$1.wasm"; }
        check_wasm_symbols "$(wr rule)"                    "WASM Rust rule exports"                  "${WASM_RULE_SYMBOLS[@]}"                     || true
        check_wasm_symbols "$(wr reporter)"                "WASM Rust reporter exports"              "${WASM_REPORTER_SYMBOLS[@]}"                 || true
        check_wasm_symbols "$(wr cli_command)"             "WASM Rust cli-command exports"           "${WASM_CLI_COMMAND_SYMBOLS[@]}"              || true
        check_wasm_symbols "$(wr config_format)"           "WASM Rust config-format exports"         "${WASM_CONFIG_FORMAT_SYMBOLS[@]}"            || true
        check_wasm_symbols "$(wr profile)"                 "WASM Rust profile exports"               "${WASM_PROFILE_SYMBOLS[@]}"                  || true
        check_wasm_symbols "$(wr ai_provider)"             "WASM Rust ai-provider exports"           "${WASM_AI_PROVIDER_SYMBOLS[@]}"              || true
        check_wasm_symbols "$(wr ai_interceptor)"          "WASM Rust ai-interceptor exports"        "${WASM_AI_INTERCEPTOR_SYMBOLS[@]}"           || true
        check_wasm_symbols "$(wr ai_analyzer)"             "WASM Rust ai-analyzer exports"           "${WASM_AI_ANALYZER_SYMBOLS[@]}"              || true
        check_wasm_symbols "$(wr ai_prompt_processor)"     "WASM Rust ai-prompt-processor exports"   "${WASM_AI_PROMPT_PROCESSOR_SYMBOLS[@]}"      || true
        check_wasm_symbols "$(wr ai_response_transformer)" "WASM Rust ai-response-transformer exports" "${WASM_AI_RESPONSE_TRANSFORMER_SYMBOLS[@]}" || true
        check_wasm_symbols "$(wr ai_context_enricher)"     "WASM Rust ai-context-enricher exports"   "${WASM_AI_CONTEXT_ENRICHER_SYMBOLS[@]}"      || true
        check_wasm_symbols "$(wr ai_redactor)"             "WASM Rust ai-redactor exports"           "${WASM_AI_REDACTOR_SYMBOLS[@]}"              || true
        check_wasm_symbols "$(wr ai_token_allocator)"      "WASM Rust ai-token-allocator exports"    "${WASM_AI_TOKEN_ALLOCATOR_SYMBOLS[@]}"       || true
    else
        fail "WASM Rust plugin: build failed"
    fi
else
    skip "WASM Rust plugin (cargo or wasm32-unknown-unknown target not available)"
fi

# ── JVM Plugin ────────────────────────────────────────────────────────────────

echo ""
echo "--- JVM Plugin ---"
if command -v java &>/dev/null; then
    JVM_DIR="$EXAMPLES_DIR/standalone-jvm-plugin"
    if (cd "$JVM_DIR" && ./gradlew clean build --quiet --no-daemon 2>/dev/null); then
        JAR=$(find "$JVM_DIR/build/libs" -name "*.jar" 2>/dev/null | head -1)
        if [ -n "$JAR" ]; then
            pass "JVM plugin: jar produced ($(basename "$JAR"))"
            if command -v jar &>/dev/null; then
                check_jar_classes "$JAR" "JVM plugin" \
                    "ExampleRulePlugin" \
                    "ExampleReporterPlugin" \
                    "ExampleProviderPlugin" \
                    "ExampleCliCommandPlugin" \
                    "ExampleProfilePlugin" \
                    "ExampleAiProviderPlugin" \
                    "ExampleAiInterceptorPlugin" \
                    "ExampleAiAnalyzerPlugin" \
                    "ExampleAiPromptProcessorPlugin" \
                    "ExampleAiResponseTransformerPlugin" \
                    "ExampleAiContextEnricherPlugin" \
                    "ExampleAiRedactorPlugin" \
                    "ExampleAiTokenAllocatorPlugin" || true
            else
                skip "JVM plugin class check (jar command not found)"
            fi
        else
            fail "JVM plugin: no jar found in build/libs"
        fi
    else
        fail "JVM plugin: build failed"
    fi
else
    skip "JVM plugin (java not found)"
fi

echo ""
echo "========================="
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
echo "========================="

[ $FAIL -eq 0 ] && exit 0 || exit 1
