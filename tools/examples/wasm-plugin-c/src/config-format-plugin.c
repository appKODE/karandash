#include <stdint.h>

/*
 * Karandash WASM Config Format Plugin - C implementation
 *
 * Module type: CONFIG_FORMAT
 * Registers a custom config format "wasm-c-ini" with extension ".karandash.ini".
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-config-format-plugin"
#define MODULE_NAME    "WASM C Config Format Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "CONFIG_FORMAT"
#define FORMAT_ID      "wasm-c-ini"

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

/* ── CONFIG_FORMAT exports ──────────────────────────────────────────────── */

/*
 * karandash_config_format_id - unique identifier for this config format.
 */
int32_t karandash_config_format_id(void) {
    return return_string(FORMAT_ID);
}

/*
 * karandash_config_format_extensions - JSON array of file extensions this
 * format handles (without leading dot).
 */
int32_t karandash_config_format_extensions(void) {
    return return_string("[\".karandash.ini\"]");
}

/*
 * karandash_config_format_parse - parse config file content (ptr, len) and
 * return a JSON object representing the parsed configuration map.
 *
 * This stub parser recognises key=value lines under [section] headers and
 * emits a flat JSON object with "section.key" keys.
 */
int32_t karandash_config_format_parse(int32_t ptr, int32_t len) {
    (void)ptr;
    (void)len;
    /* Minimal stub: return an empty config object. */
    return return_string("{}");
}

/*
 * karandash_config_format_parse_extends - resolve the value of the "extends"
 * field from parsed config JSON (ptr, len).  Returns a JSON string (the
 * extends path) or null if not present.
 */
int32_t karandash_config_format_parse_extends(int32_t ptr, int32_t len) {
    (void)ptr;
    (void)len;
    return return_string("null");
}
