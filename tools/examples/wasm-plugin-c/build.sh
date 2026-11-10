#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RULE_OUT="$SCRIPT_DIR/plugin.wasm"
REPORTER_OUT="$SCRIPT_DIR/reporter-plugin.wasm"
PROVIDER_OUT="$SCRIPT_DIR/provider-plugin.wasm"
MUTATOR_OUT="$SCRIPT_DIR/mutator-plugin.wasm"
COMMENT_POSTER_OUT="$SCRIPT_DIR/comment-poster-plugin.wasm"
CLI_CMD_OUT="$SCRIPT_DIR/cli-command-plugin.wasm"
CFG_FMT_OUT="$SCRIPT_DIR/config-format-plugin.wasm"
PROFILE_OUT="$SCRIPT_DIR/profile-plugin.wasm"
AI_PROVIDER_OUT="$SCRIPT_DIR/ai-provider-plugin.wasm"
AI_INTERCEPTOR_OUT="$SCRIPT_DIR/ai-interceptor-plugin.wasm"
AI_ANALYZER_OUT="$SCRIPT_DIR/ai-analyzer-plugin.wasm"
AI_PROMPT_PROC_OUT="$SCRIPT_DIR/ai-prompt-processor-plugin.wasm"
AI_RESP_TRANS_OUT="$SCRIPT_DIR/ai-response-transformer-plugin.wasm"
AI_CTX_ENRICH_OUT="$SCRIPT_DIR/ai-context-enricher-plugin.wasm"
AI_REDACTOR_OUT="$SCRIPT_DIR/ai-redactor-plugin.wasm"
AI_TOKEN_ALLOC_OUT="$SCRIPT_DIR/ai-token-allocator-plugin.wasm"
SCRIPT_OUT="$SCRIPT_DIR/script-plugin.wasm"

build_with_emcc() {
    local src="$1"
    local out="$2"
    local exports="$3"
    emcc "$src" \
        -O2 \
        -s STANDALONE_WASM \
        -s "EXPORTED_FUNCTIONS=$exports" \
        -s ERROR_ON_UNDEFINED_SYMBOLS=0 \
        --no-entry \
        -o "$out"
}

build_with_clang() {
    local src="$1"
    local out="$2"
    clang \
        --target=wasm32 \
        -nostdlib \
        -Wl,--no-entry \
        -Wl,--export-all \
        -O2 \
        -o "$out" \
        "$src" \
        "$SCRIPT_DIR/src/wasm-builtins.c"
}

COMMON_EXPORTS='"_karandash_abi_version","_karandash_alloc","_karandash_dealloc","_karandash_module_id","_karandash_module_name","_karandash_module_version","_karandash_module_type","_karandash_describe","_karandash_initialize","_karandash_shutdown"'

RULE_EXPORTS="[$COMMON_EXPORTS,\"_karandash_contributed_rules\",\"_karandash_validate\"]"
REPORTER_EXPORTS="[$COMMON_EXPORTS,\"_karandash_format_id\",\"_karandash_report\"]"
PROVIDER_EXPORTS="[$COMMON_EXPORTS,\"_karandash_fetch_merge_request\"]"
MUTATOR_EXPORTS="[$COMMON_EXPORTS,\"_karandash_add_labels\",\"_karandash_remove_labels\",\"_karandash_set_draft\",\"_karandash_mutator_update_title\",\"_karandash_mutator_update_description\"]"
COMMENT_POSTER_EXPORTS="[$COMMON_EXPORTS,\"_karandash_post_comment\"]"
CLI_CMD_EXPORTS="[$COMMON_EXPORTS,\"_karandash_cli_commands\"]"
CFG_FMT_EXPORTS="[$COMMON_EXPORTS,\"_karandash_config_format_id\",\"_karandash_config_format_extensions\",\"_karandash_config_format_parse\",\"_karandash_config_format_parse_extends\"]"
PROFILE_EXPORTS="[$COMMON_EXPORTS,\"_karandash_profile_id\",\"_karandash_profile_summary\",\"_karandash_profile_tags\",\"_karandash_profile_rule_configs\",\"_karandash_profile_reporter_configs\"]"
AI_PROVIDER_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_complete\"]"
AI_INTERCEPTOR_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_intercept\"]"
AI_ANALYZER_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_analyze\"]"
AI_PROMPT_PROC_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_process_prompt\",\"_karandash_ai_process_system_prompt\"]"
AI_RESP_TRANS_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_transform_response\"]"
AI_CTX_ENRICH_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_enrich_context\"]"
AI_REDACTOR_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_redact\"]"
AI_TOKEN_ALLOC_EXPORTS="[$COMMON_EXPORTS,\"_karandash_ai_allocate_tokens\"]"
SCRIPT_EXPORTS="[$COMMON_EXPORTS,\"_karandash_execute_scripts\"]"

if command -v emcc &>/dev/null; then
    echo "Building with Emscripten..."
    build_with_emcc "$SCRIPT_DIR/src/plugin.c"                        "$RULE_OUT"            "$RULE_EXPORTS"
    echo "Built: $RULE_OUT"
    build_with_emcc "$SCRIPT_DIR/src/reporter-plugin.c"               "$REPORTER_OUT"        "$REPORTER_EXPORTS"
    echo "Built: $REPORTER_OUT"
    build_with_emcc "$SCRIPT_DIR/src/provider-plugin.c"              "$PROVIDER_OUT"        "$PROVIDER_EXPORTS"
    echo "Built: $PROVIDER_OUT"
    build_with_emcc "$SCRIPT_DIR/src/mutator-plugin.c"               "$MUTATOR_OUT"         "$MUTATOR_EXPORTS"
    echo "Built: $MUTATOR_OUT"
    build_with_emcc "$SCRIPT_DIR/src/comment-poster-plugin.c"        "$COMMENT_POSTER_OUT"  "$COMMENT_POSTER_EXPORTS"
    echo "Built: $COMMENT_POSTER_OUT"
    build_with_emcc "$SCRIPT_DIR/src/cli-command-plugin.c"            "$CLI_CMD_OUT"         "$CLI_CMD_EXPORTS"
    echo "Built: $CLI_CMD_OUT"
    build_with_emcc "$SCRIPT_DIR/src/config-format-plugin.c"          "$CFG_FMT_OUT"         "$CFG_FMT_EXPORTS"
    echo "Built: $CFG_FMT_OUT"
    build_with_emcc "$SCRIPT_DIR/src/profile-plugin.c"                "$PROFILE_OUT"         "$PROFILE_EXPORTS"
    echo "Built: $PROFILE_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-provider-plugin.c"            "$AI_PROVIDER_OUT"     "$AI_PROVIDER_EXPORTS"
    echo "Built: $AI_PROVIDER_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-interceptor-plugin.c"         "$AI_INTERCEPTOR_OUT"  "$AI_INTERCEPTOR_EXPORTS"
    echo "Built: $AI_INTERCEPTOR_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-analyzer-plugin.c"            "$AI_ANALYZER_OUT"     "$AI_ANALYZER_EXPORTS"
    echo "Built: $AI_ANALYZER_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-prompt-processor-plugin.c"    "$AI_PROMPT_PROC_OUT"  "$AI_PROMPT_PROC_EXPORTS"
    echo "Built: $AI_PROMPT_PROC_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-response-transformer-plugin.c" "$AI_RESP_TRANS_OUT"  "$AI_RESP_TRANS_EXPORTS"
    echo "Built: $AI_RESP_TRANS_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-context-enricher-plugin.c"    "$AI_CTX_ENRICH_OUT"   "$AI_CTX_ENRICH_EXPORTS"
    echo "Built: $AI_CTX_ENRICH_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-redactor-plugin.c"            "$AI_REDACTOR_OUT"     "$AI_REDACTOR_EXPORTS"
    echo "Built: $AI_REDACTOR_OUT"
    build_with_emcc "$SCRIPT_DIR/src/ai-token-allocator-plugin.c"     "$AI_TOKEN_ALLOC_OUT"  "$AI_TOKEN_ALLOC_EXPORTS"
    echo "Built: $AI_TOKEN_ALLOC_OUT"
    build_with_emcc "$SCRIPT_DIR/src/script-plugin.c"                 "$SCRIPT_OUT"          "$SCRIPT_EXPORTS"
    echo "Built: $SCRIPT_OUT"
    exit 0
fi

if command -v clang &>/dev/null; then
    echo "Building with clang --target=wasm32..."
    build_with_clang "$SCRIPT_DIR/src/plugin.c"                        "$RULE_OUT"
    echo "Built: $RULE_OUT"
    build_with_clang "$SCRIPT_DIR/src/reporter-plugin.c"               "$REPORTER_OUT"
    echo "Built: $REPORTER_OUT"
    build_with_clang "$SCRIPT_DIR/src/provider-plugin.c"              "$PROVIDER_OUT"
    echo "Built: $PROVIDER_OUT"
    build_with_clang "$SCRIPT_DIR/src/mutator-plugin.c"               "$MUTATOR_OUT"
    echo "Built: $MUTATOR_OUT"
    build_with_clang "$SCRIPT_DIR/src/comment-poster-plugin.c"        "$COMMENT_POSTER_OUT"
    echo "Built: $COMMENT_POSTER_OUT"
    build_with_clang "$SCRIPT_DIR/src/cli-command-plugin.c"            "$CLI_CMD_OUT"
    echo "Built: $CLI_CMD_OUT"
    build_with_clang "$SCRIPT_DIR/src/config-format-plugin.c"          "$CFG_FMT_OUT"
    echo "Built: $CFG_FMT_OUT"
    build_with_clang "$SCRIPT_DIR/src/profile-plugin.c"                "$PROFILE_OUT"
    echo "Built: $PROFILE_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-provider-plugin.c"            "$AI_PROVIDER_OUT"
    echo "Built: $AI_PROVIDER_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-interceptor-plugin.c"         "$AI_INTERCEPTOR_OUT"
    echo "Built: $AI_INTERCEPTOR_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-analyzer-plugin.c"            "$AI_ANALYZER_OUT"
    echo "Built: $AI_ANALYZER_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-prompt-processor-plugin.c"    "$AI_PROMPT_PROC_OUT"
    echo "Built: $AI_PROMPT_PROC_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-response-transformer-plugin.c" "$AI_RESP_TRANS_OUT"
    echo "Built: $AI_RESP_TRANS_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-context-enricher-plugin.c"    "$AI_CTX_ENRICH_OUT"
    echo "Built: $AI_CTX_ENRICH_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-redactor-plugin.c"            "$AI_REDACTOR_OUT"
    echo "Built: $AI_REDACTOR_OUT"
    build_with_clang "$SCRIPT_DIR/src/ai-token-allocator-plugin.c"     "$AI_TOKEN_ALLOC_OUT"
    echo "Built: $AI_TOKEN_ALLOC_OUT"
    build_with_clang "$SCRIPT_DIR/src/script-plugin.c"                 "$SCRIPT_OUT"
    echo "Built: $SCRIPT_OUT"
    exit 0
fi

echo "ERROR: Neither emcc nor clang found. Install Emscripten or LLVM with wasm32 support."
exit 1
