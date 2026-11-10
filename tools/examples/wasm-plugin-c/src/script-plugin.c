#include <stdint.h>

/*
 * Karandash WASM Plugin - C implementation
 *
 * Script plugin: wasm-c-script-example
 *   - Demonstrates SCRIPT module type via karandash_execute_scripts.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-script-example"
#define MODULE_NAME    "WASM C Script Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "SCRIPT"

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

/* ── Script execution ───────────────────────────────────────────────────── */

/*
 * Execute scripts against the provided merge request data.
 *
 * All three parameters arrive as (ptr, len) pairs pointing into WASM linear
 * memory. The result is a length-prefixed JSON findings array written into
 * the bump heap; the pointer to that allocation is returned.
 *
 * This minimal implementation returns a single INFO finding to demonstrate
 * that the script execution entry point is wired correctly.
 */
int32_t karandash_execute_scripts(
    int32_t scripts_ptr, int32_t scripts_len,
    int32_t mr_ptr,      int32_t mr_len,
    int32_t ctx_ptr,     int32_t ctx_len
) {
    (void)scripts_ptr;
    (void)scripts_len;
    (void)mr_ptr;
    (void)mr_len;
    (void)ctx_ptr;
    (void)ctx_len;

    return return_string(
        "[{\"rule\":\"wasm-c-script\","
        "\"severity\":\"INFO\","
        "\"message\":\"script executed successfully\","
        "\"location\":null}]"
    );
}
