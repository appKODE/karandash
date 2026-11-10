#include <stdint.h>

/*
 * Karandash WASM AI Response Transformer Plugin - C implementation
 *
 * Module type: AI_RESPONSE_TRANSFORMER
 * Post-processes raw AI completion text into structured Finding objects.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-response-transformer-plugin"
#define MODULE_NAME    "WASM C AI Response Transformer Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_RESPONSE_TRANSFORMER"

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

/* ── AI_RESPONSE_TRANSFORMER exports ────────────────────────────────────── */

/*
 * karandash_ai_transform_response - convert raw AI text into Finding objects.
 *
 * response_ptr/response_len: the raw completion text from the AI provider.
 * context_ptr/context_len: JSON object with the original request context.
 *
 * Returns a JSON array of Finding objects extracted from the AI response.
 */
int32_t karandash_ai_transform_response(
    int32_t response_ptr, int32_t response_len,
    int32_t context_ptr,  int32_t context_len
) {
    (void)response_ptr;
    (void)response_len;
    (void)context_ptr;
    (void)context_len;
    /*
     * Stub: return an empty findings array. A real transformer would parse
     * the AI response text, extract severity/message/location fields, and
     * map them to Finding objects.
     */
    return return_string("[]");
}
