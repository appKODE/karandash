#include <stdint.h>

/*
 * Karandash WASM AI Context Enricher Plugin - C implementation
 *
 * Module type: AI_CONTEXT_ENRICHER
 * Enriches the AI request context with additional MR metadata before the
 * prompt is assembled.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-context-enricher-plugin"
#define MODULE_NAME    "WASM C AI Context Enricher Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_CONTEXT_ENRICHER"

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

/* ── AI_CONTEXT_ENRICHER exports ────────────────────────────────────────── */

/*
 * karandash_ai_enrich_context - add extra fields to the AI context object.
 *
 * context_ptr/context_len: JSON object with the current AI context (includes
 *   the MergeRequest, rule config, and any previously enriched fields).
 *
 * Returns a JSON object containing additional key/value pairs to merge into
 * the context before prompt assembly.  Keys already present are not
 * overwritten unless the enricher intentionally replaces them.
 */
int32_t karandash_ai_enrich_context(int32_t context_ptr, int32_t context_len) {
    (void)context_ptr;
    (void)context_len;
    /*
     * Stub: return extra metadata a real enricher might inject - e.g. team
     * ownership, linked Jira tickets, or historical MR statistics.
     */
    return return_string(
        "{"
          "\"enricher\":\"wasm-c-context-enricher\","
          "\"note\":\"No additional context injected by stub enricher.\""
        "}"
    );
}
