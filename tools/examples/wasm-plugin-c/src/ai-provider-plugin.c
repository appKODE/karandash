#include <stdint.h>

/*
 * Karandash WASM AI Provider Plugin - C implementation
 *
 * Module type: AI_PROVIDER
 * Provides a stub AI completion backend named "wasm-c-ai".
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-provider-plugin"
#define MODULE_NAME    "WASM C AI Provider Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_PROVIDER"

/* ── Bump allocator (no libc/WASI required) ─────────────────────────────── */

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

/* ── AI_PROVIDER exports ────────────────────────────────────────────────── */

/*
 * karandash_ai_complete - process an AI completion request.
 *
 * request_ptr/request_len: JSON object with fields:
 *   systemPrompt  - system instruction string
 *   userPrompt    - user message string
 *   model         - model identifier string (may be empty)
 *   maxTokens     - integer token budget
 *
 * Returns a JSON object with fields:
 *   content       - the completion text
 *   model         - model used
 *   inputTokens   - tokens consumed by the prompt
 *   outputTokens  - tokens in the response
 */
int32_t karandash_ai_complete(int32_t request_ptr, int32_t request_len) {
    (void)request_ptr;
    (void)request_len;
    /*
     * Stub: return a canned response. A real implementation would call an
     * external AI API via host-provided I/O imports or a pre-opened socket.
     */
    return return_string(
        "{"
          "\"content\":\"LGTM — this MR looks good from the WASM C AI provider stub.\","
          "\"model\":\"wasm-c-stub-v1\","
          "\"inputTokens\":10,"
          "\"outputTokens\":15"
        "}"
    );
}
