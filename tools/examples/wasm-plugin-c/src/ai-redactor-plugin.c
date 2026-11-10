#include <stdint.h>

/*
 * Karandash WASM AI Redactor Plugin - C implementation
 *
 * Module type: AI_REDACTOR
 * Redacts secrets and sensitive data from MR diffs before they are sent to
 * an AI provider.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-ai-redactor-plugin"
#define MODULE_NAME    "WASM C AI Redactor Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_REDACTOR"

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

/* ── AI_REDACTOR exports ────────────────────────────────────────────────── */

/*
 * karandash_ai_redact - redact sensitive content from text before AI submission.
 *
 * text_ptr/text_len: the raw text (diff, description, commit message, etc.)
 *   that may contain secrets, tokens, or PII.
 * cfg_ptr/cfg_len: redactor config (patterns, replacement strings, etc.).
 *
 * Returns a JSON object with fields:
 *   redacted  - the sanitised text with sensitive values replaced
 *   redactions - integer count of replacements made
 */
int32_t karandash_ai_redact(
    int32_t text_ptr, int32_t text_len,
    int32_t cfg_ptr,  int32_t cfg_len
) {
    (void)cfg_ptr;
    (void)cfg_len;

    /*
     * Stub: pass the text through unchanged and report zero redactions.
     * A real redactor would scan for patterns like API keys, passwords,
     * private keys, and email addresses, replacing matches with [REDACTED].
     */
    if (text_len <= 0) {
        return return_string("{\"redacted\":\"\",\"redactions\":0}");
    }

    /* Build response JSON: {"redacted":"<original>","redactions":0}
     * We copy the original text into a JSON string value. */
    const char *prefix = "{\"redacted\":\"";
    const char *suffix = "\",\"redactions\":0}";

    uint32_t prefix_len = 0;
    while (prefix[prefix_len]) prefix_len++;
    uint32_t suffix_len = 0;
    while (suffix[suffix_len]) suffix_len++;

    uint32_t utext_len = (uint32_t)text_len;
    uint32_t body_len  = prefix_len + utext_len + suffix_len;
    uint32_t total     = 4 + body_len;

    int32_t out_ptr = heap_offset;
    heap_offset += (int32_t)total;

    unsigned char *buf = &heap[out_ptr];
    buf[0] = (unsigned char)(body_len & 0xFF);
    buf[1] = (unsigned char)((body_len >> 8)  & 0xFF);
    buf[2] = (unsigned char)((body_len >> 16) & 0xFF);
    buf[3] = (unsigned char)((body_len >> 24) & 0xFF);

    uint32_t off = 4;
    for (uint32_t i = 0; i < prefix_len; i++) buf[off++] = (unsigned char)prefix[i];
    const unsigned char *src = &heap[text_ptr];
    for (uint32_t i = 0; i < utext_len; i++) buf[off++] = src[i];
    for (uint32_t i = 0; i < suffix_len; i++) buf[off++] = (unsigned char)suffix[i];

    return out_ptr;
}
