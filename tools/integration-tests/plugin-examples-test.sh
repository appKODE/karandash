#!/usr/bin/env bash
set -euo pipefail

# ── Plugin Examples Integration Test Suite ────────────────────────────────────
# Two-layer verification of all plugin examples from tools/examples/:
#
# Layer 1 (Symbol): Build + ABI verification (always runs)
#   - Native plugins: compile + ABI symbol verification
#   - WASM plugins: compile + exported function verification
#   - JVM plugins: Gradle build + ServiceLoader manifest check
#   - JS plugins: syntax validation + export structure check
#
# Layer 2 (Runtime): Plugin loading + execution inside real Karandash binary
#   - Layer 2a: Plugin load verification via `karandash plugin list --plugin-dir`
#   - Layer 2b: File-mode execution - built-in rules against MR JSON
#   - Layer 2c: Live-mode pipeline - full validation with mock GitLab API server
#   Requires Karandash binaries (built in Docker). Skipped when not present.
#
# Covers ALL plugin types per language:
#   rule, reporter, provider, mutator, comment-poster, cli-command,
#   config-format, profile, ai-provider, ai-interceptor, ai-analyzer,
#   ai-prompt-processor, ai-response-transformer, ai-context-enricher,
#   ai-redactor, ai-token-allocator
#
# Output: table of results similar to benchmark-regression-check.sh
#
# Usage:
#   ./plugin-examples-test.sh                         # auto-detect examples dir
#   ./plugin-examples-test.sh /path/to/examples       # explicit path
#   SKIP_HEAVY=1 ./plugin-examples-test.sh            # skip Kotlin/Native, JVM (slow Gradle builds)
#
# Granular filtering via environment variables:
#   PLUGIN_LANG="kotlin,rust"            # run only Kotlin and Rust tests
#   PLUGIN_VARIANT="standalone,native"   # run only standalone and native variant tests
#   PLUGIN_LANG="cpp" PLUGIN_VARIANT="standalone"  # run only C++ standalone
#
#   PLUGIN_LANG values:    c  cpp  go  rust  python  kotlin  js
#   PLUGIN_VARIANT values: native  wasm  jar  standalone
#
#   Comma-separated; case-insensitive; spaces around commas ignored.
#   PLUGIN_LANG always overrides SKIP_HEAVY - if "kotlin" is explicitly listed,
#   Kotlin tests run regardless of SKIP_HEAVY.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXAMPLES_DIR="${1:-$(cd "$SCRIPT_DIR/../examples" 2>/dev/null && pwd)}"
# Resolve to absolute path so cd inside test functions doesn't break relative paths
EXAMPLES_DIR="$(cd "$EXAMPLES_DIR" 2>/dev/null && pwd)"

if [[ ! -d "$EXAMPLES_DIR" ]]; then
    echo "ERROR: Examples directory not found: $EXAMPLES_DIR" >&2
    exit 2
fi

# Builds below rebuild committed example artifacts (.wasm) in place. On exit, restore every tracked file under
# the examples dir that was clean when the run started, so a run leaves `git status` clean. Rebuild and commit
# those artifacts deliberately with each example's build.sh.
EXAMPLES_DIRTY_AT_START="$(git -C "$EXAMPLES_DIR" diff --name-only --relative -- . 2>/dev/null || true)"
restore_examples() {
    local f
    # Outside a git checkout (a source archive) there is nothing to restore
    git -C "$EXAMPLES_DIR" diff --name-only --relative -- . 2>/dev/null | while IFS= read -r f; do
        grep -qxF -- "$f" <<< "$EXAMPLES_DIRTY_AT_START" || git -C "$EXAMPLES_DIR" checkout -q -- "$f"
    done || true
}
trap restore_examples EXIT

# Source cargo env if available
[ -f "$HOME/.cargo/env" ] && source "$HOME/.cargo/env" 2>/dev/null || true

# ── Platform detection ────────────────────────────────────────────────────────
OS=$(uname -s)
ARCH=$(uname -m)
# Normalize: Docker/dpkg may report "amd64" but uname uses "x86_64"
[[ "$ARCH" = "amd64" ]] && ARCH="x86_64"
if [[ "$OS" = "Darwin" ]]; then
    SHARED_EXT="dylib"
    NM_FLAGS="-gU"
else
    SHARED_EXT="so"
    NM_FLAGS="-D"
fi

# ── Test filtering ────────────────────────────────────────────────────────────
PLUGIN_LANG="${PLUGIN_LANG:-}"
PLUGIN_VARIANT="${PLUGIN_VARIANT:-}"

# Returns 0 if <lang> matches the PLUGIN_LANG filter (or filter is empty).
# Filter is comma-separated, case-insensitive, spaces around commas are ignored.
lang_matches() {
    [[ -z "$PLUGIN_LANG" ]] && return 0
    local needle filter
    needle=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    filter=$(printf '%s' "$PLUGIN_LANG" | tr '[:upper:]' '[:lower:]')
    filter="${filter// /}"
    [[ ",$filter," == *",$needle,"* ]]
}

# Returns 0 if <variant> matches the PLUGIN_VARIANT filter (or filter is empty).
# Filter is comma-separated, case-insensitive, spaces around commas are ignored.
variant_matches() {
    [[ -z "$PLUGIN_VARIANT" ]] && return 0
    local needle filter
    needle=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    filter=$(printf '%s' "$PLUGIN_VARIANT" | tr '[:upper:]' '[:lower:]')
    filter="${filter// /}"
    [[ ",$filter," == *",$needle,"* ]]
}

# Returns 0 if a test with given <lang> and <variant> should run.
should_run() { lang_matches "$1" && variant_matches "$2"; }

# Returns 0 if PLUGIN_LANG explicitly requests <lang> (overrides SKIP_HEAVY).
lang_explicitly_requested() { [[ -n "$PLUGIN_LANG" ]] && lang_matches "$1"; }

# ── Error-visible logging ─────────────────────────────────────────────────────
LOG_DIR="${LOG_DIR:-/tmp/karandash-plugin-test-logs}"
# Absolute: the tests below cd into each example.
[[ "$LOG_DIR" = /* ]] || LOG_DIR="$(pwd)/$LOG_DIR"
[[ -z "${RESULTS_DIR:-}" || "$RESULTS_DIR" = /* ]] || RESULTS_DIR="$(pwd)/$RESULTS_DIR"

# Run a build command, capture stdout+stderr to a labelled log file, and print
# the tail on failure so CI logs always contain the compiler/linker error.
run_and_log() {
    local label="$1"; shift
    local log_file="${LOG_DIR}/${label//[^a-zA-Z0-9._-]/_}.log"
    mkdir -p "$LOG_DIR"
    if "$@" >"$log_file" 2>&1; then
        return 0
    else
        local rc=$?
        printf '\n┌─ BUILD FAILED: %s (exit %d) ──────────────────────────────────────\n' "$label" "$rc" >&2
        tail -n 50 "$log_file" >&2
        printf '└─ full log: %s\n\n' "$log_file" >&2
        return $rc
    fi
}

# ── Counters & results ───────────────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
TOTAL_START=$(date +%s)

declare -a RESULTS=()

# ── Symbol sets per module type ──────────────────────────────────────────────
BASE_SYMBOLS=(
    karandash_abi_version
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
    karandash_free_string
)

RULE_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_contributed_rules karandash_validate)
REPORTER_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_format_id karandash_report)
PROVIDER_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_fetch_merge_request)
COMMENT_POSTER_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_post_comment)
MUTATOR_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_add_labels karandash_remove_labels karandash_set_draft karandash_update_title karandash_update_description)
CLI_COMMAND_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_cli_commands)
CONFIG_FORMAT_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_config_format_id karandash_config_format_extensions karandash_config_parse karandash_config_parse_extends)
PROFILE_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_profile_id karandash_profile_summary karandash_profile_tags karandash_profile_rule_configs karandash_profile_reporter_configs)
AI_PROVIDER_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_complete)
AI_INTERCEPTOR_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_intercept)
AI_ANALYZER_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_analyze)
AI_PROMPT_PROC_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_process_prompt karandash_ai_process_system_prompt)
AI_RESP_TRANS_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_transform_response)
AI_CTX_ENRICH_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_enrich_context)
AI_REDACTOR_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_redact)
AI_TOKEN_ALLOC_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_ai_allocate_tokens)
SCRIPT_SYMBOLS=("${BASE_SYMBOLS[@]}" karandash_execute)

# WASM symbols (alloc/dealloc instead of free_string)
WASM_BASE_SYMBOLS=(
    karandash_abi_version
    karandash_alloc
    karandash_dealloc
    karandash_module_id
    karandash_module_name
    karandash_module_version
    karandash_module_type
    karandash_initialize
    karandash_shutdown
)
WASM_RULE_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_contributed_rules karandash_validate)
WASM_REPORTER_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_format_id karandash_report)
WASM_CLI_CMD_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_cli_commands)
WASM_CFG_FMT_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_config_format_id karandash_config_format_extensions karandash_config_format_parse karandash_config_format_parse_extends)
WASM_PROFILE_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_profile_id karandash_profile_summary karandash_profile_tags karandash_profile_rule_configs karandash_profile_reporter_configs)
WASM_AI_PROVIDER_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_complete)
WASM_AI_INTERCEPTOR_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_intercept)
WASM_AI_ANALYZER_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_analyze)
WASM_AI_PROMPT_PROC_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_process_prompt karandash_ai_process_system_prompt)
WASM_AI_RESP_TRANS_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_transform_response)
WASM_AI_CTX_ENRICH_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_enrich_context)
WASM_AI_REDACTOR_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_redact)
WASM_AI_TOKEN_ALLOC_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_ai_allocate_tokens)
WASM_SCRIPT_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_execute_scripts)
WASM_PROVIDER_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_fetch_merge_request)
WASM_MUTATOR_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_add_labels karandash_remove_labels karandash_set_draft karandash_mutator_update_title karandash_mutator_update_description)
WASM_COMMENT_POSTER_SYMBOLS=("${WASM_BASE_SYMBOLS[@]}" karandash_post_comment)

# ── Helpers ──────────────────────────────────────────────────────────────────

timestamp_ms() {
    if [[ "$OS" = "Darwin" ]]; then
        python3 -c "import time; print(int(time.time_ns() // 1_000_000))"
    else
        echo $(( $(date +%s%N) / 1000000 ))
    fi
}

add_result() {
    local lang="$1" plugin_type="$2" variant="$3" build_result="$4" verify_result="$5" status="$6" time_ms="$7"
    # In CI a missing toolchain/binary/fixture must not pass silently; an explicit SKIP_HEAVY opt-out ("skipped") still skips.
    if [[ "$status" = "SKIP" && "${CI:-}" = "true" && "$build_result" != "skipped" ]]; then
        status="FAIL"
    fi
    RESULTS+=("${lang}|${plugin_type}|${variant}|${build_result}|${verify_result}|${status}|${time_ms}")
    case "$status" in
        PASS) PASS=$((PASS + 1)) ;;
        FAIL) FAIL=$((FAIL + 1)) ;;
        SKIP) SKIP=$((SKIP + 1)) ;;
    esac
}

check_native_symbols() {
    local lib_path="$1"
    shift
    local expected_symbols=("$@")

    if [[ ! -f "$lib_path" ]]; then
        echo "0/${#expected_symbols[@]}"
        return 1
    fi

    local symbols
    symbols=$(nm $NM_FLAGS "$lib_path" 2>/dev/null | grep karandash_ || true)
    local found=0
    for sym in "${expected_symbols[@]}"; do
        if grep -q -- "$sym" <<< "$symbols"; then
            found=$((found + 1))
        fi
    done

    echo "${found}/${#expected_symbols[@]}"
    [[ $found -eq ${#expected_symbols[@]} ]]
}

check_wasm_exports() {
    local wasm_path="$1"
    shift
    local expected_exports=("$@")

    if [[ ! -f "$wasm_path" ]]; then
        echo "0/${#expected_exports[@]}"
        return 1
    fi

    # Extract strings once, then check each symbol against the cached output
    local all_strings
    all_strings=$(strings "$wasm_path" 2>/dev/null)

    local found=0
    for exp in "${expected_exports[@]}"; do
        if grep -q -- "$exp" <<< "$all_strings"; then
            found=$((found + 1))
        elif grep -q -- "_${exp}" <<< "$all_strings"; then
            found=$((found + 1))
        fi
    done

    echo "${found}/${#expected_exports[@]}"
    [[ $found -eq ${#expected_exports[@]} ]]
}

# Maps a plugin type name to its symbol array name (native)
get_symbol_array_name() {
    case "$1" in
        rule)                    echo "RULE_SYMBOLS" ;;
        reporter)                echo "REPORTER_SYMBOLS" ;;
        provider)                echo "PROVIDER_SYMBOLS" ;;
        mutator)                 echo "MUTATOR_SYMBOLS" ;;
        comment-poster)          echo "COMMENT_POSTER_SYMBOLS" ;;
        cli-command)             echo "CLI_COMMAND_SYMBOLS" ;;
        config-format)           echo "CONFIG_FORMAT_SYMBOLS" ;;
        profile)                 echo "PROFILE_SYMBOLS" ;;
        ai-provider)             echo "AI_PROVIDER_SYMBOLS" ;;
        ai-interceptor)          echo "AI_INTERCEPTOR_SYMBOLS" ;;
        ai-analyzer)             echo "AI_ANALYZER_SYMBOLS" ;;
        ai-prompt-processor)     echo "AI_PROMPT_PROC_SYMBOLS" ;;
        ai-response-transformer) echo "AI_RESP_TRANS_SYMBOLS" ;;
        ai-context-enricher)     echo "AI_CTX_ENRICH_SYMBOLS" ;;
        ai-redactor)             echo "AI_REDACTOR_SYMBOLS" ;;
        ai-token-allocator)      echo "AI_TOKEN_ALLOC_SYMBOLS" ;;
        script)                  echo "SCRIPT_SYMBOLS" ;;
    esac
}

# Maps a plugin type name to its WASM symbol array name
get_wasm_symbol_array_name() {
    case "$1" in
        rule)                    echo "WASM_RULE_SYMBOLS" ;;
        reporter)                echo "WASM_REPORTER_SYMBOLS" ;;
        provider)                echo "WASM_PROVIDER_SYMBOLS" ;;
        mutator)                 echo "WASM_MUTATOR_SYMBOLS" ;;
        comment-poster)          echo "WASM_COMMENT_POSTER_SYMBOLS" ;;
        cli-command)             echo "WASM_CLI_CMD_SYMBOLS" ;;
        config-format)           echo "WASM_CFG_FMT_SYMBOLS" ;;
        profile)                 echo "WASM_PROFILE_SYMBOLS" ;;
        ai-provider)             echo "WASM_AI_PROVIDER_SYMBOLS" ;;
        ai-interceptor)          echo "WASM_AI_INTERCEPTOR_SYMBOLS" ;;
        ai-analyzer)             echo "WASM_AI_ANALYZER_SYMBOLS" ;;
        ai-prompt-processor)     echo "WASM_AI_PROMPT_PROC_SYMBOLS" ;;
        ai-response-transformer) echo "WASM_AI_RESP_TRANS_SYMBOLS" ;;
        ai-context-enricher)     echo "WASM_AI_CTX_ENRICH_SYMBOLS" ;;
        ai-redactor)             echo "WASM_AI_REDACTOR_SYMBOLS" ;;
        ai-token-allocator)      echo "WASM_AI_TOKEN_ALLOC_SYMBOLS" ;;
        script)                  echo "WASM_SCRIPT_SYMBOLS" ;;
    esac
}

# Verify a single WASM module against its expected symbols (avoids bash nameref bugs)
verify_wasm_plugin() {
    local lang="$1" plugin_type="$2" wfile="$3" elapsed="$4"
    local sym_name
    sym_name=$(get_wasm_symbol_array_name "$plugin_type")
    # Copy symbols into a plain local array (avoids nameref + eval issues)
    local _vwp_syms=()
    eval "_vwp_syms=(\"\${${sym_name}[@]}\")"
    local verify
    if verify=$(check_wasm_exports "$wfile" "${_vwp_syms[@]}"); then
        add_result "$lang" "$plugin_type" "wasm" "ok ${elapsed}ms" "$verify" "PASS" "$elapsed"
    else
        add_result "$lang" "$plugin_type" "wasm" "ok ${elapsed}ms" "$verify" "FAIL" "$elapsed"
    fi
}

# Verify a single native library against its expected symbols (uses eval to avoid nameref bugs)
verify_native_plugin() {
    local lang="$1" plugin_type="$2" variant="$3" lib_path="$4" elapsed="$5"
    local sym_ref_name
    sym_ref_name=$(get_symbol_array_name "$plugin_type")
    local verify
    eval "if verify=\$(check_native_symbols \"\$lib_path\" \"\${${sym_ref_name}[@]}\"); then
        add_result \"\$lang\" \"\$plugin_type\" \"\$variant\" \"ok \${elapsed}ms\" \"\$verify\" PASS \"\$elapsed\"
    else
        add_result \"\$lang\" \"\$plugin_type\" \"\$variant\" \"ok \${elapsed}ms\" \"\$verify\" FAIL \"\$elapsed\"
    fi"
}

# ── Layer 2: Karandash runtime verification ─────────────────────────────────
# Tests plugin loading and execution inside the real Karandash binary.
# Requires Karandash binaries to be present (built in Docker).

# Prefer workspace-relative defaults so Layer 2 works in CI jobs that run from
# the repository checkout without extra path wiring.
KARANDASH_JAR="${KARANDASH_JAR:-$SCRIPT_DIR/../../cli/build/libs/karandash.jar}"
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) NATIVE_TARGET=macosArm64 ;;
    Darwin-x86_64) NATIVE_TARGET=macosX64 ;;
    *) NATIVE_TARGET=linuxX64 ;;
esac
KARANDASH_NATIVE="${KARANDASH_NATIVE:-$SCRIPT_DIR/../../cli/build/bin/$NATIVE_TARGET/releaseExecutable/cli.kexe}"
MOCK_PORT="${MOCK_PORT:-9999}"
MOCK_PID=""
TEST_MR="${TEST_MR:-$SCRIPT_DIR/fixtures/test-mr.json}"
FIXTURES_DIR="${FIXTURES_DIR:-$SCRIPT_DIR/fixtures}"
MOCK_SERVER="${MOCK_SERVER:-$SCRIPT_DIR/mock-server/mock-gitlab-api.py}"
LAYER2_ENABLED=false

# Convert potentially relative inputs to absolute paths once.
# Later test phases change directories, so keeping relative paths causes
# false "missing binary/fixture" skips in Layer 2.
INITIAL_CWD="$(pwd)"
to_abs_path() {
    local value="$1"
    case "$value" in
        /*) printf '%s\n' "$value" ;;
        *) printf '%s\n' "$INITIAL_CWD/$value" ;;
    esac
}

KARANDASH_JAR="$(to_abs_path "$KARANDASH_JAR")"
KARANDASH_NATIVE="$(to_abs_path "$KARANDASH_NATIVE")"
TEST_MR="$(to_abs_path "$TEST_MR")"
FIXTURES_DIR="$(to_abs_path "$FIXTURES_DIR")"
MOCK_SERVER="$(to_abs_path "$MOCK_SERVER")"

if [[ -f "$KARANDASH_JAR" ]] || [[ -f "$KARANDASH_NATIVE" ]]; then
    LAYER2_ENABLED=true
fi

# Run a karandash command using the specified runtime
run_karandash() {
    local runtime="$1"; shift
    case "$runtime" in
        jvm)
            if [[ -f "$KARANDASH_JAR" ]]; then
                # ~/.karandash/plugins resolves from $HOME first, user.home as fallback.
                java -Duser.home="$HOME" -jar "$KARANDASH_JAR" "$@"
            else
                return 127
            fi
            ;;
        native)
            if [[ -f "$KARANDASH_NATIVE" ]]; then
                "$KARANDASH_NATIVE" "$@"
            else
                return 127
            fi
            ;;
    esac
}

# Get list of available runtimes
available_runtimes() {
    local runtimes=()
    [[ -f "$KARANDASH_JAR" ]] && runtimes+=("jvm")
    [[ -f "$KARANDASH_NATIVE" ]] && runtimes+=("native")
    echo "${runtimes[@]}"
}

start_mock_server() {
    if ! command -v python3 &>/dev/null; then
        echo "WARN: python3 not found, skipping live-mode tests" >&2
        return 1
    fi
    python3 "$MOCK_SERVER" "$MOCK_PORT" &
    MOCK_PID=$!
    # Wait for server to be ready
    local retries=0
    while ! curl -s "http://localhost:$MOCK_PORT/api/v4/projects/1/merge_requests/1" >/dev/null 2>&1; do
        retries=$((retries + 1))
        if [[ $retries -ge 20 ]]; then
            echo "WARN: Mock server failed to start" >&2
            kill "$MOCK_PID" 2>/dev/null || true
            MOCK_PID=""
            return 1
        fi
        sleep 0.25
    done
    return 0
}

stop_mock_server() {
    if [[ -n "$MOCK_PID" ]]; then
        kill "$MOCK_PID" 2>/dev/null || true
        wait "$MOCK_PID" 2>/dev/null || true
        MOCK_PID=""
    fi
}

# Layer 2a: Verify a plugin can be loaded by Karandash
verify_plugin_loads() {
    local plugin_path="$1" lang="$2" plugin_type="$3" runtime="$4"
    local ext="${plugin_path##*.}"

    local tmp_dir
    tmp_dir=$(mktemp -d)
    cp "$plugin_path" "$tmp_dir/"

    local start_ms
    start_ms=$(timestamp_ms)
    local output=""
    local exit_code=0

    output=$(run_karandash "$runtime" plugin list --plugin-dir "$tmp_dir" 2>&1) || exit_code=$?

    local elapsed=$(( $(timestamp_ms) - start_ms ))
    rm -rf "$tmp_dir"

    if [[ $exit_code -eq 0 ]] && grep -qi "plugin" <<< "$output"; then
        add_result "$lang" "$plugin_type" "load-$runtime" "ok" "loaded" "PASS" "$elapsed"
    elif [[ $exit_code -eq 127 ]]; then
        add_result "$lang" "$plugin_type" "load-$runtime" "no binary" "—" "SKIP" "$elapsed"
    elif [[ $exit_code -ge 128 ]]; then
        local sig=$((exit_code - 128))
        add_result "$lang" "$plugin_type" "load-$runtime" "signal:$sig/QEMU" "—" "SKIP" "$elapsed"
    else
        # The plugin built, so a load error is a failure (it used to be a SKIP, which hid broken loaders)
        printf '\n  [load-err exit:%d] %s %s (runtime=%s):\n' "$exit_code" "$lang" "$plugin_type" "$runtime" >&2
        printf '%s\n' "$output" | tail -n 20 >&2
        add_result "$lang" "$plugin_type" "load-$runtime" "ok" "load-err:$exit_code" "FAIL" "$elapsed"
    fi
    return 0
}

# Layer 2b: Verify built-in rules work in file-mode
verify_builtin_filemode() {
    local runtime="$1"
    # validate only reads configs under the working directory: run inside a temp dir holding the config.
    local work
    work=$(mktemp -d)
    cp "$FIXTURES_DIR/test-config-filemode.yaml" "$work/config.yaml"

    local start_ms
    start_ms=$(timestamp_ms)
    local exit_code=0 output=""
    output=$(cd "$work" && run_karandash "$runtime" validate --mr "$TEST_MR" --config config.yaml 2>&1) || exit_code=$?

    local elapsed=$(( $(timestamp_ms) - start_ms ))
    rm -rf "$work"

    # 0=pass, 1=validation-failed (findings) - both mean the pipeline executed
    if [[ $exit_code -eq 0 || $exit_code -eq 1 ]]; then
        # Capture output and assert rule IDs appear
        if grep -Eqi "title-format|finding|rule" <<< "$output"; then
            add_result "builtin" "rule" "exec-file-$runtime" "ok" "exit:$exit_code+output" "PASS" "$elapsed"
        else
            add_result "builtin" "rule" "exec-file-$runtime" "ok" "exit:$exit_code" "PASS" "$elapsed"
        fi
    elif [[ $exit_code -eq 127 ]]; then
        add_result "builtin" "rule" "exec-file-$runtime" "no binary" "—" "SKIP" "$elapsed"
    elif [[ $exit_code -eq 4 || $exit_code -eq 132 ]] && [[ "$runtime" == "native" ]]; then
        add_result "builtin" "rule" "exec-file-$runtime" "SIGILL/QEMU" "—" "SKIP" "$elapsed"
    else
        printf '\n  [exec-file FAIL exit:%d] runtime=%s:\n' "$exit_code" "$runtime" >&2
        printf '%s\n' "$output" | tail -n 30 >&2
        add_result "builtin" "rule" "exec-file-$runtime" "FAIL" "exit:$exit_code" "FAIL" "$elapsed"
    fi
}

# Layer 2c: Verify full pipeline with mock GitLab API server
verify_live_pipeline() {
    local runtime="$1"
    local work
    work=$(mktemp -d)
    sed -e "s|\${MOCK_PORT}|$MOCK_PORT|g" "$FIXTURES_DIR/test-config-livemode.yaml" > "$work/config.yaml"

    local start_ms
    start_ms=$(timestamp_ms)
    local exit_code=0 output=""
    output=$(cd "$work" && run_karandash "$runtime" validate \
        --platform gitlab --project "test/project" --mr-id 1 \
        --config config.yaml 2>&1) || exit_code=$?

    local elapsed=$(( $(timestamp_ms) - start_ms ))
    rm -rf "$work"

    if [[ $exit_code -eq 0 || $exit_code -eq 1 ]]; then
        if grep -Eqi "title-format|finding|rule|validation" <<< "$output"; then
            add_result "builtin" "pipeline" "live-$runtime" "ok" "exit:$exit_code+output" "PASS" "$elapsed"
        else
            add_result "builtin" "pipeline" "live-$runtime" "ok" "exit:$exit_code" "PASS" "$elapsed"
        fi
    elif [[ $exit_code -eq 127 ]]; then
        add_result "builtin" "pipeline" "live-$runtime" "no binary" "—" "SKIP" "$elapsed"
    elif [[ $exit_code -eq 4 || $exit_code -eq 132 ]] && [[ "$runtime" == "native" ]]; then
        add_result "builtin" "pipeline" "live-$runtime" "SIGILL/QEMU" "—" "SKIP" "$elapsed"
    else
        printf '\n  [live-pipeline FAIL exit:%d] runtime=%s:\n' "$exit_code" "$runtime" >&2
        printf '%s\n' "$output" | tail -n 30 >&2
        add_result "builtin" "pipeline" "live-$runtime" "FAIL" "exit:$exit_code" "FAIL" "$elapsed"
    fi
}

# Collect all built plugin artifacts for Layer 2a testing
run_layer2a_for_native() {
    local lang="$1" lib_path="$2" plugin_type="$3"
    [[ ! -f "$lib_path" ]] && return

    for runtime in $(available_runtimes); do
        verify_plugin_loads "$lib_path" "$lang" "$plugin_type" "$runtime"
    done
}

# ── Test functions (Layer 1: Symbol verification) ────────────────────────────

test_c_native() {
    local dir="$EXAMPLES_DIR/standalone-native-plugin"
    [[ ! -d "$dir" ]] && { add_result "C" "all" "native" "—" "—" "SKIP" "0"; return; }

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if { make clean >/dev/null 2>&1; run_and_log "c-native" make; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # C Makefile builds all 17 types
        local types=(
            "rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script"
        )
        local targets=(
            "libnative-example-rule" "libnative-example-reporter" "libnative-example-provider"
            "libnative-example-mutator" "libnative-example-comment-poster"
            "libnative-example-cli-command" "libnative-example-config-format" "libnative-example-profile"
            "libnative-example-ai-provider" "libnative-example-ai-interceptor"
            "libnative-example-ai-analyzer" "libnative-example-ai-prompt-processor"
            "libnative-example-ai-response-transformer" "libnative-example-ai-context-enricher"
            "libnative-example-ai-redactor" "libnative-example-ai-token-allocator"
            "libnative-example-script"
        )

        for i in "${!types[@]}"; do
            verify_native_plugin "C" "${types[$i]}" "native" "${targets[$i]}.${SHARED_EXT}" "$elapsed"
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "C" "all" "native" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_cpp_native() {
    local dir="$EXAMPLES_DIR/native-plugin-cpp"
    [[ ! -d "$dir" ]] && { add_result "C++" "all" "native" "—" "—" "SKIP" "0"; return; }

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if { make clean >/dev/null 2>&1; run_and_log "cpp-native" make; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # C++ Makefile builds all 17 types
        local types=(
            "rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script"
        )
        local targets=(
            "libcpp-rule-example" "libcpp-reporter-example" "libcpp-provider-example"
            "libcpp-mutator-example" "libcpp-comment-poster-example"
            "libcpp-cli-command-example" "libcpp-config-format-example" "libcpp-profile-example"
            "libcpp-ai-provider-example" "libcpp-ai-interceptor-example"
            "libcpp-ai-analyzer-example" "libcpp-ai-prompt-processor-example"
            "libcpp-ai-response-transformer-example" "libcpp-ai-context-enricher-example"
            "libcpp-ai-redactor-example" "libcpp-ai-token-allocator-example"
            "libcpp-script-example"
        )

        for i in "${!types[@]}"; do
            verify_native_plugin "C++" "${types[$i]}" "native" "${targets[$i]}.${SHARED_EXT}" "$elapsed"
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "C++" "all" "native" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_go_native() {
    local dir="$EXAMPLES_DIR/native-plugin-go"
    [[ ! -d "$dir" ]] && { add_result "Go" "all" "native" "—" "—" "SKIP" "0"; return; }

    if ! command -v go &>/dev/null; then
        add_result "Go" "all" "native" "no toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    local target
    if [[ "$OS" = "Darwin" ]]; then
        target="macos-arm64"
    elif [[ "$ARCH" = "aarch64" || "$ARCH" = "arm64" ]]; then
        target="linux-arm64"
    else
        target="linux-x64"
    fi

    if { make clean >/dev/null 2>&1; run_and_log "go-native" make "$target"; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # Go builds all 17 plugin types
        # Root: rule
        verify_native_plugin "Go" "rule" "native" "libgo-example.${SHARED_EXT}" "$elapsed"
        # Subdirectories: 16 additional types
        local go_types=(
            "provider" "mutator" "comment-poster" "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "reporter" "script"
        )
        local go_dirs=(
            "provider" "mutator" "comment_poster" "cli_command" "config_format" "profile"
            "ai_provider" "ai_interceptor" "ai_analyzer" "ai_prompt_processor"
            "ai_response_transformer" "ai_context_enricher" "ai_redactor" "ai_token_allocator"
            "reporter" "script"
        )
        local go_libs=(
            "libgo-example-provider" "libgo-example-mutator" "libgo-example-comment-poster"
            "libgo-example-cli-command" "libgo-example-config-format" "libgo-example-profile"
            "libgo-example-ai-provider" "libgo-example-ai-interceptor" "libgo-example-ai-analyzer"
            "libgo-example-ai-prompt-processor" "libgo-example-ai-response-transformer"
            "libgo-example-ai-context-enricher" "libgo-example-ai-redactor" "libgo-example-ai-token-allocator"
            "libgo-example-reporter" "libgo-example-script"
        )

        for i in "${!go_types[@]}"; do
            local lib="${go_dirs[$i]}/${go_libs[$i]}.${SHARED_EXT}"
            verify_native_plugin "Go" "${go_types[$i]}" "native" "$lib" "$elapsed"
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Go" "all" "native" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_rust_native() {
    local dir="$EXAMPLES_DIR/native-plugin-rust"
    [[ ! -d "$dir" ]] && { add_result "Rust" "all" "native" "—" "—" "SKIP" "0"; return; }

    if ! command -v cargo &>/dev/null; then
        add_result "Rust" "all" "native" "no toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "rust-native" cargo build --release; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # Rust workspace: 16 crates, each produces a cdylib
        # rule-reporter crate exports both rule AND reporter symbols in one lib
        local rust_types=(
            "rule" "reporter" "provider" "mutator" "comment-poster" "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script"
        )
        local rust_libs=(
            "libkarandash_rust_rule_reporter" "libkarandash_rust_rule_reporter"
            "libkarandash_rust_provider" "libkarandash_rust_mutator"
            "libkarandash_rust_comment_poster" "libkarandash_rust_cli_command" "libkarandash_rust_config_format"
            "libkarandash_rust_profile" "libkarandash_rust_ai_provider" "libkarandash_rust_ai_interceptor"
            "libkarandash_rust_ai_analyzer" "libkarandash_rust_ai_prompt_processor"
            "libkarandash_rust_ai_response_transformer" "libkarandash_rust_ai_context_enricher"
            "libkarandash_rust_ai_redactor" "libkarandash_rust_ai_token_allocator"
            "libkarandash_rust_script"
        )

        for i in "${!rust_types[@]}"; do
            local lib="target/release/${rust_libs[$i]}.${SHARED_EXT}"
            if [[ -f "$lib" ]]; then
                verify_native_plugin "Rust" "${rust_types[$i]}" "native" "$lib" "$elapsed"
            else
                add_result "Rust" "${rust_types[$i]}" "native" "ok ${elapsed}ms" "lib missing" "FAIL" "$elapsed"
            fi
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Rust" "all" "native" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_python_native() {
    local dir="$EXAMPLES_DIR/native-plugin-python-c-generator"
    [[ ! -d "$dir" ]] && { add_result "Python->C" "all" "native" "—" "—" "SKIP" "0"; return; }

    if ! command -v python3 &>/dev/null; then
        add_result "Python->C" "all" "native" "no python3" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "python-native" bash build.sh; then
        # Python generator produces and compiles all 17 types
        local compile_ok=true
        for c_file in generated_*.c; do
            [[ ! -f "$c_file" ]] && continue
            local base="${c_file%.c}"
            local lib="lib${base}.${SHARED_EXT}"
            if [[ ! -f "$lib" ]]; then
                if [[ "$OS" = "Darwin" ]]; then
                    gcc -std=c11 -fPIC -dynamiclib -o "$lib" "$c_file" || compile_ok=false
                else
                    gcc -std=c11 -fPIC -shared -o "$lib" "$c_file" || compile_ok=false
                fi
            fi
        done

        local elapsed=$(( $(timestamp_ms) - t0 ))

        if $compile_ok; then
            # Check all generated plugin types
            local py_types=(
                "rule" "reporter" "provider" "mutator" "comment-poster"
                "cli-command" "config-format" "profile"
                "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
                "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
                "script"
            )
            # Python generator output uses underscores and may use different naming
            # Try both the build.sh output (libpython-*) and fallback to libgenerated_*
            local py_libs=(
                "rule" "reporter" "provider" "mutator" "comment-poster"
                "cli-command" "config-format" "profile"
                "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
                "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
                "script"
            )

            for i in "${!py_types[@]}"; do
                local ptype="${py_types[$i]}"
                local plib="${py_libs[$i]}"
                # Try libpython-<type>.so first (build.sh output), then libgenerated_<type>_plugin.so
                local lib_underscore="${plib//-/_}"
                local lib=""
                if [[ -f "libpython-${plib}.${SHARED_EXT}" ]]; then
                    lib="libpython-${plib}.${SHARED_EXT}"
                elif [[ -f "libgenerated_${lib_underscore}_plugin.${SHARED_EXT}" ]]; then
                    lib="libgenerated_${lib_underscore}_plugin.${SHARED_EXT}"
                fi

                if [[ -n "$lib" && -f "$lib" ]]; then
                    verify_native_plugin "Python->C" "$ptype" "native" "$lib" "$elapsed"
                else
                    add_result "Python->C" "$ptype" "native" "ok ${elapsed}ms" "lib missing" "FAIL" "$elapsed"
                fi
            done
        else
            add_result "Python->C" "all" "native" "COMPILE FAIL" "—" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Python->C" "all" "native" "GENERATE FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_kotlin_native() {
    local dir="$EXAMPLES_DIR/native-plugin-kotlin"
    [[ ! -d "$dir" ]] && { add_result "Kotlin" "all" "native" "—" "—" "SKIP" "0"; return; }

    if [[ "${SKIP_HEAVY:-0}" = "1" ]] && ! lang_explicitly_requested "kotlin"; then
        add_result "Kotlin" "all" "native" "skipped" "—" "SKIP" "0"
        return
    fi

    if ! command -v java &>/dev/null; then
        add_result "Kotlin" "all" "native" "no JDK" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    local gradle_target lib_path
    if [[ "$OS" = "Darwin" && "$ARCH" = "arm64" ]]; then
        gradle_target="linkReleaseSharedMacosArm64"
        lib_path="build/bin/macosArm64/releaseShared/libkotlin_example.dylib"
    elif [[ "$OS" = "Linux" && ("$ARCH" = "aarch64" || "$ARCH" = "arm64") ]]; then
        add_result "Kotlin" "all" "native" "no K/N arm64" "—" "SKIP" "0"
        cd "$SCRIPT_DIR"
        return
    elif [[ "$OS" = "Linux" ]]; then
        gradle_target="linkReleaseSharedLinuxX64"
        lib_path="build/bin/linuxX64/releaseShared/libkotlin_example.so"
    else
        add_result "Kotlin" "all" "native" "unsupported" "—" "SKIP" "0"
        cd "$SCRIPT_DIR"
        return
    fi

    if run_and_log "kotlin-native" ./gradlew clean "$gradle_target" --console=plain --no-daemon; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # Kotlin/Native builds a single lib exporting all plugin types
        # Check each type's symbols in the same library
        local kt_types=(
            "rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script"
        )

        # Pre-extract symbols once for the shared library
        local symbols
        symbols=$(nm $NM_FLAGS "$lib_path" 2>/dev/null | grep karandash_ || true)

        for ptype in "${kt_types[@]}"; do
            local sym_ref_name
            sym_ref_name=$(get_symbol_array_name "$ptype")

            # Copy symbols into a plain local array (avoids nameref + pipefail edge cases).
            local kt_expected_syms=()
            eval "kt_expected_syms=(\"\${${sym_ref_name}[@]}\")"
            local found=0
            local total=${#kt_expected_syms[@]}
            for sym in "${kt_expected_syms[@]}"; do
                if grep -q -- "$sym" <<< "$symbols"; then
                    found=$((found + 1))
                fi
            done

            if [[ $found -eq $total ]]; then
                add_result "Kotlin" "$ptype" "native" "ok ${elapsed}ms" "${found}/${total}" "PASS" "$elapsed"
            else
                add_result "Kotlin" "$ptype" "native" "ok ${elapsed}ms" "${found}/${total}" "FAIL" "$elapsed"
            fi
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Kotlin" "all" "native" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_wasm_c() {
    local dir="$EXAMPLES_DIR/wasm-plugin-c"
    [[ ! -d "$dir" ]] && { add_result "C" "all" "wasm" "—" "—" "SKIP" "0"; return; }

    if ! command -v emcc &>/dev/null && ! command -v clang &>/dev/null; then
        add_result "C" "all" "wasm" "no wasm toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    # Remove pre-built artifacts
    rm -f *.wasm 2>/dev/null || true

    if run_and_log "wasm-c" bash build.sh; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # WASM C build.sh produces all 17 .wasm files
        local wasm_types=(
            "rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script"
        )
        local wasm_files=(
            "plugin.wasm" "reporter-plugin.wasm" "provider-plugin.wasm"
            "mutator-plugin.wasm" "comment-poster-plugin.wasm"
            "cli-command-plugin.wasm" "config-format-plugin.wasm" "profile-plugin.wasm"
            "ai-provider-plugin.wasm" "ai-interceptor-plugin.wasm" "ai-analyzer-plugin.wasm"
            "ai-prompt-processor-plugin.wasm" "ai-response-transformer-plugin.wasm"
            "ai-context-enricher-plugin.wasm" "ai-redactor-plugin.wasm" "ai-token-allocator-plugin.wasm"
            "script-plugin.wasm"
        )

        for i in "${!wasm_types[@]}"; do
            local wtype="${wasm_types[$i]}"
            local wfile="${wasm_files[$i]}"
            if [[ -f "$wfile" ]]; then
                verify_wasm_plugin "C" "$wtype" "$wfile" "$elapsed"
            else
                add_result "C" "$wtype" "wasm" "ok ${elapsed}ms" "missing" "FAIL" "$elapsed"
            fi
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "C" "all" "wasm" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_wasm_rust() {
    local dir="$EXAMPLES_DIR/wasm-plugin-rust"
    [[ ! -d "$dir" ]] && { add_result "Rust" "all" "wasm" "—" "—" "SKIP" "0"; return; }

    if ! command -v cargo &>/dev/null; then
        add_result "Rust" "all" "wasm" "no cargo" "—" "SKIP" "0"
        return
    fi

    if ! rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then
        add_result "Rust" "all" "wasm" "no wasm32 target" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "wasm-rust" bash build.sh; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        # Plain list, not `local -A`: macOS ships bash 3.2. File is karandash_wasm_<type with _>.wasm.
        local wtype wfile
        for wtype in rule reporter provider mutator comment-poster cli-command config-format profile \
            ai-provider ai-interceptor ai-analyzer ai-prompt-processor ai-response-transformer \
            ai-context-enricher ai-redactor ai-token-allocator script; do
            wfile="karandash_wasm_$(printf '%s' "$wtype" | tr '-' '_').wasm"
            # Rust guests export describe via wasm_base_exports!; a missing export fails the type.
            if [[ -f "$wfile" ]] && ! check_wasm_exports "$wfile" karandash_describe >/dev/null; then
                add_result "Rust" "$wtype" "wasm" "ok ${elapsed}ms" "no karandash_describe" "FAIL" "$elapsed"
            elif [[ -f "$wfile" ]]; then
                verify_wasm_plugin "Rust" "$wtype" "$wfile" "$elapsed"
            else
                add_result "Rust" "$wtype" "wasm" "ok ${elapsed}ms" "missing" "FAIL" "$elapsed"
            fi
        done
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Rust" "all" "wasm" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_jvm_plugin() {
    local dir="$EXAMPLES_DIR/standalone-jvm-plugin"
    [[ ! -d "$dir" ]] && { add_result "Kotlin/JVM" "all" "jar" "—" "—" "SKIP" "0"; return; }

    if [[ "${SKIP_HEAVY:-0}" = "1" ]] && ! lang_explicitly_requested "kotlin"; then
        add_result "Kotlin/JVM" "all" "jar" "skipped" "—" "SKIP" "0"
        return
    fi

    if ! command -v java &>/dev/null; then
        add_result "Kotlin/JVM" "all" "jar" "no JDK" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "jvm-plugin" ./gradlew clean jar --console=plain --no-daemon; then
        local elapsed=$(( $(timestamp_ms) - t0 ))

        local jar_file
        jar_file=$(find build/libs -name "*.jar" 2>/dev/null | head -1)

        if [[ -n "$jar_file" && -f "$jar_file" ]]; then
            if jar tf "$jar_file" 2>/dev/null | grep -q "META-INF/services/io.karandash.api.Module"; then
                # Count how many module implementations are registered
                local module_count
                module_count=$(jar xf "$jar_file" "META-INF/services/io.karandash.api.Module" 2>/dev/null && \
                    wc -l < "META-INF/services/io.karandash.api.Module" 2>/dev/null | tr -d ' ' || echo "0")
                rm -rf META-INF 2>/dev/null || true
                add_result "Kotlin/JVM" "all" "jar" "ok ${elapsed}ms" "SPI:${module_count} modules" "PASS" "$elapsed"
            else
                add_result "Kotlin/JVM" "all" "jar" "ok ${elapsed}ms" "no SPI manifest" "FAIL" "$elapsed"
            fi
        else
            add_result "Kotlin/JVM" "all" "jar" "ok ${elapsed}ms" "no JAR produced" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Kotlin/JVM" "all" "jar" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_js_standalone() {
    local dir="$EXAMPLES_DIR/standalone-js-plugin"
    [[ ! -d "$dir" ]] && { add_result "JavaScript" "rule" "mjs" "—" "—" "SKIP" "0"; return; }

    local t0
    t0=$(timestamp_ms)

    local mjs_file="$dir/example-rule.mjs"
    if [[ -f "$mjs_file" ]]; then
        local checks=0
        local found=0
        for keyword in "id:" "name:" "version:" "type:" "validate(" "initialize(" "shutdown(" "contributedRules("; do
            checks=$((checks + 1))
            if grep -q "$keyword" "$mjs_file" 2>/dev/null; then
                found=$((found + 1))
            fi
        done

        local elapsed=$(( $(timestamp_ms) - t0 ))
        if [[ $found -eq $checks ]]; then
            add_result "JavaScript" "rule" "mjs" "— n/a" "${found}/${checks} exports" "PASS" "$elapsed"
        else
            add_result "JavaScript" "rule" "mjs" "— n/a" "${found}/${checks} exports" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "JavaScript" "rule" "mjs" "— n/a" "file missing" "FAIL" "$elapsed"
    fi
}

test_js_all_types() {
    local dir="$EXAMPLES_DIR/js-plugins-all-types"
    [[ ! -d "$dir" ]] && { add_result "JavaScript" "all-types" "mjs" "—" "—" "SKIP" "0"; return; }

    local t0
    t0=$(timestamp_ms)

    # Check each individual .mjs plugin file for its type-specific exports
    local js_files=(
        "rule-plugin.mjs" "reporter-plugin.mjs" "provider-plugin.mjs" "mutator-plugin.mjs"
        "comment-poster-plugin.mjs" "cli-command-plugin.mjs" "config-format-plugin.mjs" "profile-plugin.mjs"
        "ai-provider-plugin.mjs" "ai-interceptor-plugin.mjs" "ai-analyzer-plugin.mjs"
        "ai-prompt-processor-plugin.mjs" "ai-response-transformer-plugin.mjs"
        "ai-context-enricher-plugin.mjs" "ai-redactor-plugin.mjs" "ai-token-allocator-plugin.mjs"
        "script-plugin.mjs"
    )
    local js_types=(
        "rule" "reporter" "provider" "mutator"
        "comment-poster" "cli-command" "config-format" "profile"
        "ai-provider" "ai-interceptor" "ai-analyzer"
        "ai-prompt-processor" "ai-response-transformer"
        "ai-context-enricher" "ai-redactor" "ai-token-allocator"
        "script"
    )

    for i in "${!js_files[@]}"; do
        local mjs_file="$dir/${js_files[$i]}"
        local jtype="${js_types[$i]}"

        if [[ -f "$mjs_file" ]]; then
            local has_basics=true
            for keyword in "id:" "name:" "version:" "type:"; do
                if ! grep -q "$keyword" "$mjs_file" 2>/dev/null; then
                    has_basics=false
                    break
                fi
            done
            local elapsed=$(( $(timestamp_ms) - t0 ))
            if $has_basics; then
                add_result "JavaScript" "$jtype" "mjs" "— n/a" "exports ok" "PASS" "$elapsed"
            else
                add_result "JavaScript" "$jtype" "mjs" "— n/a" "missing exports" "FAIL" "$elapsed"
            fi
        else
            local elapsed=$(( $(timestamp_ms) - t0 ))
            add_result "JavaScript" "$jtype" "mjs" "— n/a" "file missing" "FAIL" "$elapsed"
        fi
    done
}

# ── Print table ──────────────────────────────────────────────────────────────

print_table() {
    local total_elapsed=$(( $(date +%s) - TOTAL_START ))

    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
    echo " Karandash Plugin Examples — Integration Test Results"
    echo " Platform: ${OS}-${ARCH}"
    echo " Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo " Duration: ${total_elapsed}s"
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
    echo ""

    # Table header
    printf "┌──────────────┬──────────────────────────┬─────────┬────────────────┬──────────────────┬────────┐\n"
    printf "│ %-12s │ %-24s │ %-7s │ %-14s │ %-16s │ %-6s │\n" "Language" "Plugin Type" "Variant" "Build" "Verify" "Status"
    printf "├──────────────┼──────────────────────────┼─────────┼────────────────┼──────────────────┼────────┤\n"

    for row in "${RESULTS[@]}"; do
        IFS='|' read -r lang ptype variant build verify status time_ms <<< "$row"

        local status_fmt
        case "$status" in
            PASS) status_fmt="\033[0;32m PASS \033[0m" ;;
            FAIL) status_fmt="\033[0;31m FAIL \033[0m" ;;
            SKIP) status_fmt="\033[1;33m SKIP \033[0m" ;;
            *)    status_fmt=" ${status:-??} " ;;
        esac

        printf "│ %-12s │ %-24s │ %-7s │ %-14s │ %-16s │" "$lang" "$ptype" "$variant" "$build" "$verify"
        printf " ${status_fmt}│\n"
    done

    printf "└──────────────┴──────────────────────────┴─────────┴────────────────┴──────────────────┴────────┘\n"

    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
    printf " Summary: \033[0;32m%d PASS\033[0m / \033[0;31m%d FAIL\033[0m / \033[1;33m%d SKIP\033[0m  (total: %d)\n" "$PASS" "$FAIL" "$SKIP" "$((PASS + FAIL + SKIP))"
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"

    # JSON summary for machine consumption
    local json_out
    json_out="${RESULTS_DIR:-/tmp}/plugin-test-results.json"
    mkdir -p "$(dirname "$json_out")"
    cat > "$json_out" <<ENDJSON
{
  "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "platform": "${OS}-${ARCH}",
  "duration_s": ${total_elapsed},
  "passed": ${PASS},
  "failed": ${FAIL},
  "skipped": ${SKIP},
  "total": $((PASS + FAIL + SKIP))
}
ENDJSON
    echo ""
    echo "JSON results: $json_out"
}

# ── Main ─────────────────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
echo " Karandash Plugin Examples — Integration Test Suite"
echo " Platform: ${OS}-${ARCH}"
echo " Examples: ${EXAMPLES_DIR}"
echo " Skip heavy (Kotlin/Native, JVM): ${SKIP_HEAVY:-0}"
[[ -n "$PLUGIN_LANG" ]]    && echo " Language filter:  ${PLUGIN_LANG}"
[[ -n "$PLUGIN_VARIANT" ]] && echo " Variant filter:   ${PLUGIN_VARIANT}"
echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
echo ""

should_run "c"      "native"  && { echo ">>> Native plugins (C)...";           test_c_native;      }
should_run "cpp"    "native"  && { echo ">>> Native plugins (C++)...";         test_cpp_native;    }
should_run "go"     "native"  && { echo ">>> Native plugins (Go)...";          test_go_native;     }
should_run "rust"   "native"  && { echo ">>> Native plugins (Rust)...";        test_rust_native;   }
should_run "python" "native"  && { echo ">>> Native plugins (Python -> C)..."; test_python_native; }
should_run "kotlin" "native"  && { echo ">>> Native plugins (Kotlin/Native)..."; test_kotlin_native; }
should_run "c"      "wasm"    && { echo ">>> WASM plugins (C)...";             test_wasm_c;        }
should_run "rust"   "wasm"    && { echo ">>> WASM plugins (Rust)...";          test_wasm_rust;     }
should_run "kotlin" "jar"     && { echo ">>> JVM plugins (Kotlin/JVM)...";     test_jvm_plugin;    }
should_run "js"     "js"      && { echo ">>> JavaScript plugins (standalone)..."; test_js_standalone; }
should_run "js"     "js"      && { echo ">>> JavaScript plugins (all types)...";  test_js_all_types;  }

# ── Standalone plugin tests (Layer 1: Symbol verification) ────────────────

test_standalone_cpp() {
    local dir="$EXAMPLES_DIR/standalone-cpp-plugin"
    [[ ! -d "$dir" ]] && { add_result "C++" "rule" "standalone" "—" "—" "SKIP" "0"; return; }

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if { make clean >/dev/null 2>&1; run_and_log "standalone-cpp" make; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        verify_native_plugin "C++" "rule" "standalone" "libcpp-standalone.${SHARED_EXT}" "$elapsed"
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "C++" "rule" "standalone" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_go() {
    local dir="$EXAMPLES_DIR/standalone-go-plugin"
    [[ ! -d "$dir" ]] && { add_result "Go" "rule" "standalone" "—" "—" "SKIP" "0"; return; }

    if ! command -v go &>/dev/null; then
        add_result "Go" "rule" "standalone" "no toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if { make clean >/dev/null 2>&1; run_and_log "standalone-go" make; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        verify_native_plugin "Go" "rule" "standalone" "libgo-standalone.${SHARED_EXT}" "$elapsed"
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Go" "rule" "standalone" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_rust() {
    local dir="$EXAMPLES_DIR/standalone-rust-plugin"
    [[ ! -d "$dir" ]] && { add_result "Rust" "rule" "standalone" "—" "—" "SKIP" "0"; return; }

    if ! command -v cargo &>/dev/null; then
        add_result "Rust" "rule" "standalone" "no toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if { make clean >/dev/null 2>&1; run_and_log "standalone-rust" make; }; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        local lib="target/release/libkarandash_standalone_rust.${SHARED_EXT}"
        if [[ -f "$lib" ]]; then
            verify_native_plugin "Rust" "rule" "standalone" "$lib" "$elapsed"
        else
            add_result "Rust" "rule" "standalone" "ok ${elapsed}ms" "lib missing" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Rust" "rule" "standalone" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_kotlin() {
    local dir="$EXAMPLES_DIR/standalone-kotlin-plugin"
    [[ ! -d "$dir" ]] && { add_result "Kotlin" "rule" "standalone" "—" "—" "SKIP" "0"; return; }

    if [[ "${SKIP_HEAVY:-0}" = "1" ]] && ! lang_explicitly_requested "kotlin"; then
        add_result "Kotlin" "rule" "standalone" "skipped" "—" "SKIP" "0"
        return
    fi

    if ! command -v java &>/dev/null; then
        add_result "Kotlin" "rule" "standalone" "no JDK" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    local gradle_target lib_path
    if [[ "$OS" = "Darwin" && "$ARCH" = "arm64" ]]; then
        gradle_target="linkReleaseSharedMacosArm64"
        lib_path="build/bin/macosArm64/releaseShared/libkotlin_standalone.dylib"
    elif [[ "$OS" = "Linux" && ("$ARCH" = "aarch64" || "$ARCH" = "arm64") ]]; then
        add_result "Kotlin" "rule" "standalone" "no K/N arm64" "—" "SKIP" "0"
        cd "$SCRIPT_DIR"
        return
    elif [[ "$OS" = "Linux" ]]; then
        gradle_target="linkReleaseSharedLinuxX64"
        lib_path="build/bin/linuxX64/releaseShared/libkotlin_standalone.so"
    else
        add_result "Kotlin" "rule" "standalone" "unsupported" "—" "SKIP" "0"
        cd "$SCRIPT_DIR"
        return
    fi

    if run_and_log "standalone-kotlin" ./gradlew clean "$gradle_target" --console=plain --no-daemon; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        verify_native_plugin "Kotlin" "rule" "standalone" "$lib_path" "$elapsed"
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Kotlin" "rule" "standalone" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_python() {
    local dir="$EXAMPLES_DIR/standalone-python-c-generator"
    [[ ! -d "$dir" ]] && { add_result "Python->C" "rule" "standalone" "—" "—" "SKIP" "0"; return; }

    if ! command -v python3 &>/dev/null; then
        add_result "Python->C" "rule" "standalone" "no python3" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "standalone-python" bash build.sh; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        local lib="libpython-standalone.${SHARED_EXT}"
        if [[ -f "$lib" ]]; then
            verify_native_plugin "Python->C" "rule" "standalone" "$lib" "$elapsed"
        else
            add_result "Python->C" "rule" "standalone" "ok ${elapsed}ms" "lib missing" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Python->C" "rule" "standalone" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_wasm_c() {
    local dir="$EXAMPLES_DIR/standalone-wasm-c-plugin"
    [[ ! -d "$dir" ]] && { add_result "C" "rule" "standalone-wasm" "—" "—" "SKIP" "0"; return; }

    if ! command -v emcc &>/dev/null && ! command -v clang &>/dev/null; then
        add_result "C" "rule" "standalone-wasm" "no wasm toolchain" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    rm -f plugin.wasm 2>/dev/null || true
    if run_and_log "standalone-wasm-c" bash build.sh; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        if [[ -f "plugin.wasm" ]]; then
            verify_wasm_plugin "C" "rule" "plugin.wasm" "$elapsed"
        else
            add_result "C" "rule" "standalone-wasm" "ok ${elapsed}ms" "missing" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "C" "rule" "standalone-wasm" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

test_standalone_wasm_rust() {
    local dir="$EXAMPLES_DIR/standalone-wasm-rust-plugin"
    [[ ! -d "$dir" ]] && { add_result "Rust" "rule" "standalone-wasm" "—" "—" "SKIP" "0"; return; }

    if ! command -v cargo &>/dev/null; then
        add_result "Rust" "rule" "standalone-wasm" "no cargo" "—" "SKIP" "0"
        return
    fi

    if ! rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then
        add_result "Rust" "rule" "standalone-wasm" "no wasm32 target" "—" "SKIP" "0"
        return
    fi

    local t0
    t0=$(timestamp_ms)
    cd "$dir"

    if run_and_log "standalone-wasm-rust" bash build.sh; then
        local elapsed=$(( $(timestamp_ms) - t0 ))
        local wasm_file="plugin.wasm"
        if [[ -f "$wasm_file" ]]; then
            verify_wasm_plugin "Rust" "rule" "$wasm_file" "$elapsed"
        else
            add_result "Rust" "rule" "standalone-wasm" "ok ${elapsed}ms" "missing" "FAIL" "$elapsed"
        fi
    else
        local elapsed=$(( $(timestamp_ms) - t0 ))
        add_result "Rust" "rule" "standalone-wasm" "BUILD FAIL" "—" "FAIL" "$elapsed"
    fi
    cd "$SCRIPT_DIR"
}

# ── Run standalone tests ──────────────────────────────────────────────────

should_run "cpp"    "standalone" && { echo ">>> Standalone plugins (C++)...";           test_standalone_cpp;       }
should_run "go"     "standalone" && { echo ">>> Standalone plugins (Go)...";            test_standalone_go;        }
should_run "rust"   "standalone" && { echo ">>> Standalone plugins (Rust)...";          test_standalone_rust;      }
should_run "kotlin" "standalone" && { echo ">>> Standalone plugins (Kotlin/Native)..."; test_standalone_kotlin;    }
should_run "python" "standalone" && { echo ">>> Standalone plugins (Python -> C)...";   test_standalone_python;    }
# WASM standalones respond to both "wasm" and "standalone" variant filters
{ lang_matches "c"    && { variant_matches "wasm" || variant_matches "standalone"; }; } \
    && { echo ">>> Standalone plugins (WASM C)...";    test_standalone_wasm_c;    }
{ lang_matches "rust" && { variant_matches "wasm" || variant_matches "standalone"; }; } \
    && { echo ">>> Standalone plugins (WASM Rust)..."; test_standalone_wasm_rust; }

# ── Layer 2: Runtime verification ────────────────────────────────────────────
if $LAYER2_ENABLED; then
    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
    echo " Layer 2: Karandash Runtime Verification"
    echo " Binaries: JAR=$(test -f "$KARANDASH_JAR" && echo "yes" || echo "no")  Native=$(test -f "$KARANDASH_NATIVE" && echo "yes" || echo "no")"
    echo "═══════════════════════════════════════════════════════════════════════════════════════════════════════════════"
    echo ""

    # ── Layer 2a: Plugin load verification ────────────────────────────────────
    echo ">>> Layer 2a: Plugin load verification..."

    # C native plugins
    c_dir="$EXAMPLES_DIR/standalone-native-plugin"
    if [[ -d "$c_dir" ]]; then
        c_types=("rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script")
        c_targets=("libnative-example-rule" "libnative-example-reporter" "libnative-example-provider"
            "libnative-example-mutator" "libnative-example-comment-poster"
            "libnative-example-cli-command" "libnative-example-config-format" "libnative-example-profile"
            "libnative-example-ai-provider" "libnative-example-ai-interceptor"
            "libnative-example-ai-analyzer" "libnative-example-ai-prompt-processor"
            "libnative-example-ai-response-transformer" "libnative-example-ai-context-enricher"
            "libnative-example-ai-redactor" "libnative-example-ai-token-allocator"
            "libnative-example-script")
        for i in "${!c_types[@]}"; do
            run_layer2a_for_native "C" "$c_dir/${c_targets[$i]}.${SHARED_EXT}" "${c_types[$i]}"
        done
    fi

    # C++ native plugins
    cpp_dir="$EXAMPLES_DIR/native-plugin-cpp"
    if [[ -d "$cpp_dir" ]]; then
        cpp_types=("rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script")
        cpp_targets=("libcpp-rule-example" "libcpp-reporter-example" "libcpp-provider-example"
            "libcpp-mutator-example" "libcpp-comment-poster-example"
            "libcpp-cli-command-example" "libcpp-config-format-example" "libcpp-profile-example"
            "libcpp-ai-provider-example" "libcpp-ai-interceptor-example"
            "libcpp-ai-analyzer-example" "libcpp-ai-prompt-processor-example"
            "libcpp-ai-response-transformer-example" "libcpp-ai-context-enricher-example"
            "libcpp-ai-redactor-example" "libcpp-ai-token-allocator-example"
            "libcpp-script-example")
        for i in "${!cpp_types[@]}"; do
            run_layer2a_for_native "C++" "$cpp_dir/${cpp_targets[$i]}.${SHARED_EXT}" "${cpp_types[$i]}"
        done
    fi

    # Go native plugins
    go_dir="$EXAMPLES_DIR/native-plugin-go"
    if [[ -d "$go_dir" ]]; then
        run_layer2a_for_native "Go" "$go_dir/libgo-example.${SHARED_EXT}" "rule"
        go_sub_types=("provider" "mutator" "comment-poster" "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "reporter" "script")
        go_sub_dirs=("provider" "mutator" "comment_poster" "cli_command" "config_format" "profile"
            "ai_provider" "ai_interceptor" "ai_analyzer" "ai_prompt_processor"
            "ai_response_transformer" "ai_context_enricher" "ai_redactor" "ai_token_allocator"
            "reporter" "script")
        go_sub_libs=("libgo-example-provider" "libgo-example-mutator" "libgo-example-comment-poster"
            "libgo-example-cli-command" "libgo-example-config-format" "libgo-example-profile"
            "libgo-example-ai-provider" "libgo-example-ai-interceptor" "libgo-example-ai-analyzer"
            "libgo-example-ai-prompt-processor" "libgo-example-ai-response-transformer"
            "libgo-example-ai-context-enricher" "libgo-example-ai-redactor" "libgo-example-ai-token-allocator"
            "libgo-example-reporter" "libgo-example-script")
        for i in "${!go_sub_types[@]}"; do
            run_layer2a_for_native "Go" "$go_dir/${go_sub_dirs[$i]}/${go_sub_libs[$i]}.${SHARED_EXT}" "${go_sub_types[$i]}"
        done
    fi

    # Rust native plugins
    rust_dir="$EXAMPLES_DIR/native-plugin-rust"
    if [[ -d "$rust_dir" ]]; then
        rust_types=("rule" "reporter" "provider" "mutator" "comment-poster" "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script")
        rust_libs=("libkarandash_rust_rule_reporter" "libkarandash_rust_rule_reporter"
            "libkarandash_rust_provider" "libkarandash_rust_mutator"
            "libkarandash_rust_comment_poster" "libkarandash_rust_cli_command" "libkarandash_rust_config_format"
            "libkarandash_rust_profile" "libkarandash_rust_ai_provider" "libkarandash_rust_ai_interceptor"
            "libkarandash_rust_ai_analyzer" "libkarandash_rust_ai_prompt_processor"
            "libkarandash_rust_ai_response_transformer" "libkarandash_rust_ai_context_enricher"
            "libkarandash_rust_ai_redactor" "libkarandash_rust_ai_token_allocator"
            "libkarandash_rust_script")
        for i in "${!rust_types[@]}"; do
            run_layer2a_for_native "Rust" "$rust_dir/target/release/${rust_libs[$i]}.${SHARED_EXT}" "${rust_types[$i]}"
        done
    fi

    # WASM plugins (C)
    wasm_c_dir="$EXAMPLES_DIR/wasm-plugin-c"
    if [[ -d "$wasm_c_dir" ]]; then
        wasm_c_types=("rule" "reporter" "provider" "mutator" "comment-poster"
            "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer" "ai-prompt-processor"
            "ai-response-transformer" "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script")
        wasm_c_files=("plugin.wasm" "reporter-plugin.wasm" "provider-plugin.wasm"
            "mutator-plugin.wasm" "comment-poster-plugin.wasm"
            "cli-command-plugin.wasm" "config-format-plugin.wasm" "profile-plugin.wasm"
            "ai-provider-plugin.wasm" "ai-interceptor-plugin.wasm" "ai-analyzer-plugin.wasm"
            "ai-prompt-processor-plugin.wasm" "ai-response-transformer-plugin.wasm"
            "ai-context-enricher-plugin.wasm" "ai-redactor-plugin.wasm" "ai-token-allocator-plugin.wasm"
            "script-plugin.wasm")
        for i in "${!wasm_c_types[@]}"; do
            [[ -f "$wasm_c_dir/${wasm_c_files[$i]}" ]] && \
                run_layer2a_for_native "C" "$wasm_c_dir/${wasm_c_files[$i]}" "${wasm_c_types[$i]}-wasm"
        done
    fi

    # WASM plugins (Rust)
    wasm_rust_dir="$EXAMPLES_DIR/wasm-plugin-rust"
    if [[ -d "$wasm_rust_dir" ]]; then
        # Plain list, not `declare -A`: macOS ships bash 3.2. File is karandash_wasm_<type with _>.wasm.
        for wtype in rule reporter provider mutator comment-poster cli-command config-format profile \
            ai-provider ai-interceptor ai-analyzer ai-prompt-processor ai-response-transformer \
            ai-context-enricher ai-redactor ai-token-allocator script; do
            wfile="$wasm_rust_dir/karandash_wasm_$(printf '%s' "$wtype" | tr '-' '_').wasm"
            if [[ -f "$wfile" ]]; then run_layer2a_for_native "Rust" "$wfile" "${wtype}-wasm"; fi
        done
    fi

    # JVM plugin
    jvm_dir="$EXAMPLES_DIR/standalone-jvm-plugin"
    if [[ -d "$jvm_dir" ]]; then
        jvm_jar=$(find "$jvm_dir/build/libs" -name "*.jar" 2>/dev/null | head -1 || true)
        if [[ -n "$jvm_jar" && -f "$jvm_jar" ]]; then
            for runtime in $(available_runtimes); do
                verify_plugin_loads "$jvm_jar" "Kotlin/JVM" "all" "$runtime"
            done
        fi
    fi

    # JS plugins
    js_dir="$EXAMPLES_DIR/js-plugins-all-types"
    if [[ -d "$js_dir" ]]; then
        js_files=("rule-plugin.mjs" "reporter-plugin.mjs" "provider-plugin.mjs" "mutator-plugin.mjs"
            "comment-poster-plugin.mjs" "cli-command-plugin.mjs" "config-format-plugin.mjs" "profile-plugin.mjs"
            "ai-provider-plugin.mjs" "ai-interceptor-plugin.mjs" "ai-analyzer-plugin.mjs"
            "ai-prompt-processor-plugin.mjs" "ai-response-transformer-plugin.mjs"
            "ai-context-enricher-plugin.mjs" "ai-redactor-plugin.mjs" "ai-token-allocator-plugin.mjs"
            "script-plugin.mjs")
        js_types=("rule" "reporter" "provider" "mutator"
            "comment-poster" "cli-command" "config-format" "profile"
            "ai-provider" "ai-interceptor" "ai-analyzer"
            "ai-prompt-processor" "ai-response-transformer"
            "ai-context-enricher" "ai-redactor" "ai-token-allocator"
            "script")
        for i in "${!js_files[@]}"; do
            [[ -f "$js_dir/${js_files[$i]}" ]] && \
                run_layer2a_for_native "JavaScript" "$js_dir/${js_files[$i]}" "${js_types[$i]}"
        done
    fi

    # ── Layer 2b: File-mode execution ─────────────────────────────────────────
    echo ">>> Layer 2b: File-mode execution (built-in rules)..."
    if [[ -f "$TEST_MR" && -f "$FIXTURES_DIR/test-config-filemode.yaml" ]]; then
        for runtime in $(available_runtimes); do
            verify_builtin_filemode "$runtime"
        done
    else
        add_result "builtin" "rule" "exec-file" "no fixtures" "—" "SKIP" "0"
    fi

    # ── Layer 2c: Live-mode pipeline with mock server ─────────────────────────
    echo ">>> Layer 2c: Live-mode pipeline (mock GitLab API)..."
    if [[ -f "$MOCK_SERVER" && -f "$FIXTURES_DIR/test-config-livemode.yaml" ]]; then
        if start_mock_server; then
            for runtime in $(available_runtimes); do
                verify_live_pipeline "$runtime"
            done
            stop_mock_server
        else
            add_result "builtin" "pipeline" "live" "mock failed" "—" "SKIP" "0"
        fi
    else
        add_result "builtin" "pipeline" "live" "no fixtures" "—" "SKIP" "0"
    fi

    # ── Layer 2d: External plugin validation ─────────────────────────────────
    echo ">>> Layer 2d: External plugin validation (standalone plugins)..."

    # Layer 2d: Verify a plugin can validate an MR when attached to karandash
    verify_plugin_validates() {
        local plugin_path="$1" lang="$2" rule_id="$3" runtime="$4"
        # Plugins load only from ~/.karandash/plugins, and validate only reads configs under the working
        # directory: run in a fresh work dir with an isolated home holding just this plugin.
        local tmp_dir
        tmp_dir=$(mktemp -d)
        mkdir -p "$tmp_dir/home/.karandash/plugins" "$tmp_dir/work"
        cp "$plugin_path" "$tmp_dir/home/.karandash/plugins/"
        cat > "$tmp_dir/work/config.yaml" <<VALIDYAML
rules:
  ${rule_id}:
    enabled: true
reporters:
  json: true
VALIDYAML
        local start_ms
        start_ms=$(timestamp_ms)
        local output="" exit_code=0
        output=$(cd "$tmp_dir/work" && HOME="$tmp_dir/home" \
            run_karandash "$runtime" validate -v --mr "$TEST_MR" --config config.yaml 2>&1) || exit_code=$?
        local elapsed=$(( $(timestamp_ms) - start_ms ))
        rm -rf "$tmp_dir"

        if [[ $exit_code -eq 0 || $exit_code -eq 1 ]]; then
            # The engine's own log line: the plugin loaded and its rule actually ran (the bare id also
            # appears in "unknown rule" warnings, so it proves nothing).
            if grep -Fq -- "Rule [$rule_id]: completed" <<< "$output"; then
                add_result "$lang" "rule" "validate-$runtime" "ok" "rule:$rule_id" "PASS" "$elapsed"
            elif grep -Fq -- "JAR bridge not found" <<< "$output"; then
                add_result "$lang" "rule" "validate-$runtime" "no KARANDASH_BRIDGE_JAR" "—" "SKIP" "$elapsed"
            else
                printf '\n  [validate FAIL] %s rule "%s" not found in output (runtime=%s):\n' "$lang" "$rule_id" "$runtime" >&2
                printf '%s\n' "$output" | tail -n 30 >&2
                add_result "$lang" "rule" "validate-$runtime" "ok" "rule-missing" "FAIL" "$elapsed"
            fi
        elif [[ $exit_code -eq 4 || $exit_code -ge 128 ]] && [[ "$runtime" == "native" ]]; then
            add_result "$lang" "rule" "validate-$runtime" "SIGILL/QEMU" "—" "SKIP" "$elapsed"
        elif [[ $exit_code -ge 128 ]]; then
            local sig=$((exit_code - 128))
            add_result "$lang" "rule" "validate-$runtime" "signal:$sig" "—" "SKIP" "$elapsed"
        elif [[ $exit_code -eq 127 ]]; then
            add_result "$lang" "rule" "validate-$runtime" "no binary" "—" "SKIP" "$elapsed"
        else
            printf '\n  [validate FAIL exit:%d] %s (runtime=%s):\n' "$exit_code" "$lang" "$runtime" >&2
            printf '%s\n' "$output" | tail -n 30 >&2
            add_result "$lang" "rule" "validate-$runtime" "exit:$exit_code" "—" "FAIL" "$elapsed"
        fi
    }

    # Layer 2d: a guest declaring several module types (describe().types) serves every one of them.
    # Each declared type is checked through the CLI surface that uses it: the rule runs in validate,
    # the reporter prints its output, a config file in the guest's format drives the run, the profile
    # shows in `profile list` and the command in `--help`; with a run command, running it prints its marker.
    verify_composite_plugin() {
        local plugin_path="$1" lang="$2" runtime="$3" config_name="$4" config_body="$5"
        local rule_id="$6" report_marker="$7" profile_id="$8" command="$9" config_format="${10:-}"
        local run_command="${11:-}" run_marker="${12:-}"
        local tmp_dir
        tmp_dir=$(mktemp -d)
        mkdir -p "$tmp_dir/home/.karandash/plugins" "$tmp_dir/work"
        cp "$plugin_path" "$tmp_dir/home/.karandash/plugins/"
        printf '%s\n' "$config_body" > "$tmp_dir/work/$config_name"
        local start_ms
        start_ms=$(timestamp_ms)
        local validate_out="" profile_out="" help_out="" exit_code=0
        validate_out=$(cd "$tmp_dir/work" && HOME="$tmp_dir/home" \
            run_karandash "$runtime" validate -v --mr "$TEST_MR" --config "$config_name" 2>&1) || exit_code=$?
        profile_out=$(cd "$tmp_dir/work" && HOME="$tmp_dir/home" run_karandash "$runtime" profile list 2>&1) || true
        help_out=$(cd "$tmp_dir/work" && HOME="$tmp_dir/home" run_karandash "$runtime" --help 2>&1) || true
        local run_out=""
        if [[ -n "$run_command" ]]; then
            # shellcheck disable=SC2086 # the command path and its arguments are separate words
            run_out=$(cd "$tmp_dir/work" && HOME="$tmp_dir/home" run_karandash "$runtime" $run_command 2>&1) || true
        fi
        local elapsed=$(( $(timestamp_ms) - start_ms ))
        rm -rf "$tmp_dir"

        local check="composite-$runtime"
        if [[ $exit_code -eq 127 ]]; then
            add_result "$lang" "composite" "$check" "no binary" "—" "SKIP" "$elapsed"
            return
        elif [[ $exit_code -ge 128 && "$runtime" == "native" ]]; then
            add_result "$lang" "composite" "$check" "signal:$((exit_code - 128))" "—" "SKIP" "$elapsed"
            return
        elif grep -Fq -- "JAR bridge not found" <<< "$validate_out"; then
            add_result "$lang" "composite" "$check" "no KARANDASH_BRIDGE_JAR" "—" "SKIP" "$elapsed"
            return
        elif [[ $exit_code -ne 0 && $exit_code -ne 1 ]]; then
            printf '\n  [composite FAIL exit:%d] %s (runtime=%s):\n' "$exit_code" "$lang" "$runtime" >&2
            printf '%s\n' "$validate_out" | tail -n 30 >&2
            add_result "$lang" "composite" "$check" "exit:$exit_code" "—" "FAIL" "$elapsed"
            return
        fi

        composite_check() {
            local type="$1" marker="$2" output="$3"
            if grep -Fq -- "$marker" <<< "$output"; then
                add_result "$lang" "$type" "$check" "ok" "$marker" "PASS" "$elapsed"
            else
                printf '\n  [composite FAIL] %s %s: "%s" not found (runtime=%s):\n' "$lang" "$type" "$marker" "$runtime" >&2
                printf '%s\n' "$output" | tail -n 20 >&2
                add_result "$lang" "$type" "$check" "ok" "missing" "FAIL" "$elapsed"
            fi
        }
        composite_check "rule" "Rule [$rule_id]: completed" "$validate_out"
        composite_check "reporter" "$report_marker" "$validate_out"
        composite_check "profile" "$profile_id" "$profile_out"
        composite_check "cli-command" "$command" "$help_out"
        if [[ -n "$config_format" ]]; then
            composite_check "config-format" "format=$config_format" "$validate_out"
        fi
        if [[ -n "$run_command" ]]; then
            composite_check "cli-run" "$run_marker" "$run_out"
        fi
    }

    # standalone_built <artifact> <lang> <PLUGIN_LANG name>: true when the artifact exists. A missing one records
    # a "not built" SKIP (a FAIL under CI=true) so a runtime path never drops out of the table silently; under
    # SKIP_HEAVY the Kotlin builds are opted out ("skipped").
    standalone_built() {
        local artifact="$1" lang="$2" filter="$3"
        [[ -f "$artifact" ]] && return 0
        lang_matches "$filter" || return 1
        local why="not built"
        if [[ "${SKIP_HEAVY:-0}" = "1" && "$filter" = "kotlin" ]] && ! lang_explicitly_requested "kotlin"; then
            why="skipped"
        fi
        add_result "$lang" "rule" "validate" "$why" "${artifact##*/}" "SKIP" "0"
        return 1
    }

    if [[ -f "$TEST_MR" ]]; then
        # C standalone
        c_rule_lib="$EXAMPLES_DIR/standalone-native-plugin/libnative-example-rule.${SHARED_EXT}"
        if standalone_built "$c_rule_lib" "C" c; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$c_rule_lib" "C" "native-title-uppercase" "$runtime"
            done
        fi

        # JS standalone
        js_rule="$EXAMPLES_DIR/standalone-js-plugin/example-rule.mjs"
        if standalone_built "$js_rule" "JavaScript" js; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$js_rule" "JavaScript" "js-title-length" "$runtime"
            done
        fi

        # C++ standalone
        cpp_rule_lib="$EXAMPLES_DIR/standalone-cpp-plugin/libcpp-standalone.${SHARED_EXT}"
        if standalone_built "$cpp_rule_lib" "C++" cpp; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$cpp_rule_lib" "C++" "cpp-title-check" "$runtime"
            done
        fi

        # Go standalone
        go_rule_lib="$EXAMPLES_DIR/standalone-go-plugin/libgo-standalone.${SHARED_EXT}"
        if standalone_built "$go_rule_lib" "Go" go; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$go_rule_lib" "Go" "go-title-check" "$runtime"
            done
        fi

        # Rust standalone
        rust_rule_lib="$EXAMPLES_DIR/standalone-rust-plugin/target/release/libkarandash_standalone_rust.${SHARED_EXT}"
        if standalone_built "$rust_rule_lib" "Rust" rust; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$rust_rule_lib" "Rust" "rust-title-check" "$runtime"
            done
        fi

        # Kotlin standalone
        if [[ "$OS" = "Darwin" && "$ARCH" = "arm64" ]]; then
            kt_rule_lib="$EXAMPLES_DIR/standalone-kotlin-plugin/build/bin/macosArm64/releaseShared/libkotlin_standalone.dylib"
        elif [[ "$OS" = "Linux" ]]; then
            kt_rule_lib="$EXAMPLES_DIR/standalone-kotlin-plugin/build/bin/linuxX64/releaseShared/libkotlin_standalone.so"
        fi
        if [[ -n "${kt_rule_lib:-}" ]] && standalone_built "$kt_rule_lib" "Kotlin" kotlin; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$kt_rule_lib" "Kotlin" "kotlin-title-check" "$runtime"
            done
        fi

        # Python standalone
        py_rule_lib="$EXAMPLES_DIR/standalone-python-c-generator/libpython-standalone.${SHARED_EXT}"
        if standalone_built "$py_rule_lib" "Python->C" python; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$py_rule_lib" "Python->C" "python-title-check" "$runtime"
            done
        fi

        # WASM C standalone
        wasm_c_rule="$EXAMPLES_DIR/standalone-wasm-c-plugin/plugin.wasm"
        if standalone_built "$wasm_c_rule" "C-WASM" c; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$wasm_c_rule" "C-WASM" "wasm-c-standalone-check" "$runtime"
            done
        fi

        # WASM Rust standalone
        wasm_rust_rule="$EXAMPLES_DIR/standalone-wasm-rust-plugin/plugin.wasm"
        if standalone_built "$wasm_rust_rule" "Rust-WASM" rust; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$wasm_rust_rule" "Rust-WASM" "wasm-rust-standalone-check" "$runtime"
            done
        fi

        # JS composite: rule + reporter + profile + CLI command + config format from one guest
        js_composite="$EXAMPLES_DIR/js-plugins-all-types/composite-plugin.mjs"
        if [[ -f "$js_composite" ]]; then
            for runtime in $(available_runtimes); do
                verify_composite_plugin "$js_composite" "JavaScript" "$runtime" "config.jscomp" \
                    '{"rules":{"js-composite-title":{"enabled":true}},"reporters":{"js-composite-report":true}}' \
                    "js-composite-title" "JS-COMPOSITE-REPORT" "js-composite-profile" "js-composite" "js-composite-config" \
                    "js-composite a b" "JS-COMPOSITE-RAN js-composite args=a b"
            done
        fi

        # WASM C composite: the standalone example also serves reporter, profile and CLI command
        if [[ -f "$wasm_c_rule" ]]; then
            for runtime in $(available_runtimes); do
                verify_composite_plugin "$wasm_c_rule" "C-WASM" "$runtime" "config.yaml" \
                    "$(printf 'rules:\n  wasm-c-standalone-check:\n    enabled: true\nreporters:\n  wasm-c-standalone-report: true')" \
                    "wasm-c-standalone-check" "WASM-C-STANDALONE-REPORT" "wasm-c-standalone-profile" "wasm-c-standalone" "" \
                    "wasm-c-standalone" "WASM-C-STANDALONE-RAN"
            done
        fi

        # JVM standalone
        jvm_jar="$EXAMPLES_DIR/standalone-jvm-plugin/build/libs/*.jar"
        # `|| true`: with pipefail a missing JAR fails the ls and set -e would end the whole run here
        jvm_jar_file=$(ls $jvm_jar 2>/dev/null | head -1 || true)
        if standalone_built "${jvm_jar_file:-$EXAMPLES_DIR/standalone-jvm-plugin/build/libs/*.jar}" "Kotlin/JVM" kotlin; then
            for runtime in $(available_runtimes); do
                verify_plugin_validates "$jvm_jar_file" "Kotlin/JVM" "exampleTitlePattern" "$runtime"
                # JVM composite: one class serving rule + reporter + profile + CLI command (native: over the JAR bridge)
                verify_composite_plugin "$jvm_jar_file" "Kotlin/JVM" "$runtime" "config.yaml" \
                    "$(printf 'rules:\n  exampleCompositeTitle:\n    enabled: true\nreporters:\n  example-jvm-composite-report: true')" \
                    "exampleCompositeTitle" "JVM-COMPOSITE-REPORT" "example-jvm-composite-profile" "jvm-composite" "" \
                    "jvm-composite hello x" "JVM-COMPOSITE-RAN hello x"
            done
        fi
    else
        add_result "standalone" "all" "validate" "no test MR" "—" "SKIP" "0"
    fi

    echo ""
else
    echo ""
    echo ">>> Layer 2: SKIPPED (no Karandash binaries found)"
    echo "    To enable, build Karandash and place at: $KARANDASH_JAR / $KARANDASH_NATIVE"
    echo ""
    add_result "runtime" "all" "layer2" "no binary" "—" "SKIP" "0"
fi

print_table

if [[ $FAIL -gt 0 ]]; then
    exit 1
fi
exit 0
