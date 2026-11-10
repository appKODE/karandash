#include <stdint.h>

/*
 * Karandash WASM AI Token Allocator Plugin - C implementation
 *
 * Module type: AI_TOKEN_ALLOCATOR
 * Decides how many tokens to budget for each AI call based on MR size and
 * rule complexity.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-token-allocator-plugin"
#define MODULE_NAME    "WASM C AI Token Allocator Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_TOKEN_ALLOCATOR"

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

/* ── AI_TOKEN_ALLOCATOR exports ─────────────────────────────────────────── */

/*
 * karandash_ai_allocate_tokens - compute a token budget for an AI call.
 *
 * request_ptr/request_len: JSON object with fields:
 *   ruleId      - the rule requesting the AI call
 *   mrSize      - approximate size of the MR in lines of diff
 *   complexity  - hint string: "low", "medium", or "high"
 *   defaultMax  - the host's default maximum token limit (integer)
 *
 * Returns a JSON object with fields:
 *   maxTokens   - integer token budget to apply for this call
 *   reasoning   - human-readable explanation of the budget decision
 */
int32_t karandash_ai_allocate_tokens(int32_t request_ptr, int32_t request_len) {
    (void)request_ptr;
    (void)request_len;
    /*
     * Stub: always return 1024 tokens. A real allocator would inspect the
     * mrSize and complexity fields and scale the budget accordingly, e.g.:
     *   low     → 512
     *   medium  → 1024
     *   high    → 2048
     * capped at the host-provided defaultMax.
     */
    return return_string(
        "{"
          "\"maxTokens\":1024,"
          "\"reasoning\":\"WASM C stub allocator: fixed 1024-token budget.\""
        "}"
    );
}
