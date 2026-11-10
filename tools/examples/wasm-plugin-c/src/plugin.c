#include <stdint.h>

/*
 * Karandash WASM Plugin - C implementation
 *
 * Rule: wasm-c-title-check
 *   - Title must be non-empty and at least 5 characters long.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-example-plugin"
#define MODULE_NAME    "WASM C Example Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "RULE"
#define RULE_ID        "wasm-c-title-check"

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

/*
 * Write a length-prefixed string into the bump heap and return its pointer.
 * Layout: [u32 LE length][UTF-8 bytes]
 */
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

int32_t karandash_contributed_rules(void) {
    return return_string("[\"" RULE_ID "\"]");
}

/* ── Validation ─────────────────────────────────────────────────────────── */

/*
 * Scan a host-provided JSON buffer (ptr, len) for the value of the "title"
 * key.  Writes the value start pointer and byte length into *out_start /
 * *out_len.  Returns 1 on success, 0 if not found.
 *
 * We do simple byte scanning rather than pulling in a full JSON parser.
 * The approach is safe for well-formed Karandash MR JSON.
 */
static int extract_title(
    const unsigned char *buf, int32_t buf_len,
    const unsigned char **out_start, int32_t *out_len
) {
    /* Search for "title": */
    const char *needle = "\"title\":";
    int32_t needle_len = 8;
    int32_t i = 0;

    while (i <= buf_len - needle_len) {
        int match = 1;
        for (int32_t j = 0; j < needle_len; j++) {
            if (buf[i + j] != (unsigned char)needle[j]) { match = 0; break; }
        }
        if (match) {
            /* Skip whitespace after colon */
            int32_t pos = i + needle_len;
            while (pos < buf_len && buf[pos] == ' ') pos++;
            if (pos >= buf_len || buf[pos] != '"') return 0;
            pos++; /* skip opening quote */
            *out_start = &buf[pos];
            int32_t start = pos;
            while (pos < buf_len && buf[pos] != '"') pos++;
            *out_len = pos - start;
            return 1;
        }
        i++;
    }
    return 0;
}

int32_t karandash_validate(
    int32_t rule_id_ptr, int32_t rule_id_len,
    int32_t mr_ptr,      int32_t mr_len,
    int32_t cfg_ptr,     int32_t cfg_len
) {
    (void)cfg_ptr;
    (void)cfg_len;

    const unsigned char *rule_id = &heap[rule_id_ptr];

    /* Check rule matches */
    const char *expected = RULE_ID;
    int32_t expected_len = 0;
    while (expected[expected_len]) expected_len++;

    if (rule_id_len != expected_len) return return_string("[]");
    for (int32_t i = 0; i < rule_id_len; i++) {
        if (rule_id[i] != (unsigned char)expected[i]) return return_string("[]");
    }

    const unsigned char *mr_buf = &heap[mr_ptr];

    const unsigned char *title_start;
    int32_t title_len;
    if (!extract_title(mr_buf, mr_len, &title_start, &title_len)) {
        return return_string(
            "[{\"rule\":\"" RULE_ID "\","
            "\"severity\":\"ERROR\","
            "\"message\":\"Could not read MR title\","
            "\"location\":null}]"
        );
    }

    if (title_len == 0) {
        return return_string(
            "[{\"rule\":\"" RULE_ID "\","
            "\"severity\":\"ERROR\","
            "\"message\":\"MR title must not be empty\","
            "\"location\":null}]"
        );
    }

    if (title_len < 5) {
        return return_string(
            "[{\"rule\":\"" RULE_ID "\","
            "\"severity\":\"WARNING\","
            "\"message\":\"MR title must be at least 5 characters\","
            "\"location\":null}]"
        );
    }

    return return_string("[]");
}
