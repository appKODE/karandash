#include <stdint.h>

/*
 * Karandash WASM AI Analyzer Plugin - C implementation
 *
 * Module type: AI_ANALYZER
 * Analyzes merge request data and produces structured AI analysis results.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-analyzer-plugin"
#define MODULE_NAME    "WASM C AI Analyzer Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_ANALYZER"

/* ── Bump allocator ─────────────────────────────────────────────────────── */

static unsigned char heap[65536];
static int32_t heap_offset = 0;

int32_t karandash_alloc(int32_t size) {
    int32_t ptr = heap_offset;
    heap_offset += size;
    return ptr;
}

int32_t karandash_dealloc(int32_t ptr, int32_t len) {
    (void)ptr;
    (void)len;
    return 0;
}

/* ── String helpers ─────────────────────────────────────────────────────── */

static int32_t return_string(const char *str) {
    uint32_t len = 0;
    while (str[len]) len++;

    uint32_t total = 4 + len;
    int32_t  ptr   = heap_offset;
    heap_offset   += (int32_t)total;

    unsigned char *buf = &heap[ptr];
    buf[0] = (unsigned char)(len & 0xFF);
    buf[1] = (unsigned char)((len >> 8)  & 0xFF);
    buf[2] = (unsigned char)((len >> 16) & 0xFF);
    buf[3] = (unsigned char)((len >> 24) & 0xFF);
    for (uint32_t i = 0; i < len; i++) {
        buf[4 + i] = (unsigned char)str[i];
    }
    return ptr;
}

/* ── ABI: required exports ───────────────────────────────────────────────── */

int32_t karandash_abi_version(void) { return 1; }

int32_t karandash_module_id(void)      { return return_string(MODULE_ID); }
int32_t karandash_module_name(void)    { return return_string(MODULE_NAME); }
int32_t karandash_module_version(void) { return return_string(MODULE_VERSION); }
int32_t karandash_module_type(void)    { return return_string(MODULE_TYPE); }

/* ModuleDescriptor JSON; empty "properties" means every SDK property keeps its default. */
int32_t karandash_describe(void) {
    return return_string("{\"id\":\"" MODULE_ID "\",\"types\":[\"" MODULE_TYPE "\"],\"properties\":{}}");
}

int32_t karandash_initialize(int32_t ptr, int32_t len) {
    (void)ptr;
    (void)len;
    return 1;
}

int32_t karandash_shutdown(void) { return 1; }

/* ── AI_ANALYZER exports ────────────────────────────────────────────────── */

/*
 * karandash_ai_analyze - analyze a merge request and return findings.
 *
 * mr_ptr/mr_len:     JSON object representing the MergeRequest.
 * budget_ptr/budget_len: JSON object with "remaining" token budget.
 * model_ptr/model_len:   JSON object with "model" identifier.
 * cfg_ptr/cfg_len:       analyzer config map (includes "aiServiceAvailable").
 *
 * Returns one of two JSON shapes:
 *   1. A JSON array of Finding objects (final result).
 *   2. A JSON object with "aiRequest" key containing a completion request:
 *      {"aiRequest": {"prompt": "...", "model": "...", ...}}
 *      The host calls aiService.complete() and passes the response to
 *      karandash_ai_analyze_continue.
 */
int32_t karandash_ai_analyze(
    int32_t mr_ptr,     int32_t mr_len,
    int32_t budget_ptr, int32_t budget_len,
    int32_t model_ptr,  int32_t model_len,
    int32_t cfg_ptr,    int32_t cfg_len
) {
    (void)mr_ptr;
    (void)mr_len;
    (void)budget_ptr;
    (void)budget_len;
    (void)model_ptr;
    (void)model_len;
    (void)cfg_ptr;
    (void)cfg_len;
    /* Stub: return empty findings directly (no AI call needed). */
    return return_string("[]");
}

/*
 * karandash_ai_analyze_continue - receive AI completion response and return
 * final findings or another aiRequest for multi-turn analysis.
 *
 * response_ptr/response_len: JSON object with AI completion response fields:
 *   {"content": "...", "promptTokens": N, "completionTokens": N,
 *    "totalTokens": N, "model": "..."}
 *
 * Returns the same two shapes as karandash_ai_analyze.
 */
int32_t karandash_ai_analyze_continue(
    int32_t response_ptr, int32_t response_len
) {
    (void)response_ptr;
    (void)response_len;
    /* Stub: return empty findings. */
    return return_string("[]");
}
