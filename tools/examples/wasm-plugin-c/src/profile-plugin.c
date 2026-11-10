#include <stdint.h>

/*
 * Karandash WASM Profile Plugin - C implementation
 *
 * Module type: PROFILE
 * Provides a built-in validation profile "wasm-c-strict".
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-profile-plugin"
#define MODULE_NAME    "WASM C Profile Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "PROFILE"
#define PROFILE_ID     "wasm-c-strict"

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

/* ── PROFILE exports ────────────────────────────────────────────────────── */

/*
 * karandash_profile_id - unique identifier for this profile.
 */
int32_t karandash_profile_id(void) {
    return return_string(PROFILE_ID);
}

/*
 * karandash_profile_summary - human-readable description of this profile.
 */
int32_t karandash_profile_summary(void) {
    return return_string(
        "Strict validation profile from WASM C plugin: "
        "enforces title length, description, and commit message conventions."
    );
}

/*
 * karandash_profile_tags - JSON array of tag strings categorising the profile.
 */
int32_t karandash_profile_tags(void) {
    return return_string("[\"strict\",\"wasm\",\"example\"]");
}

/*
 * karandash_profile_rule_configs - JSON object mapping rule IDs to their
 * default config maps for this profile.
 */
int32_t karandash_profile_rule_configs(void) {
    return return_string(
        "{"
          "\"title-format\":{\"pattern\":\"^(feat|fix|chore|docs|refactor|test|style):\"},"
          "\"commit-messages\":{\"pattern\":\"^(feat|fix|chore|docs|refactor|test|style):\"},"
          "\"draft\":{\"enabled\":true}"
        "}"
    );
}

/*
 * karandash_profile_reporter_configs - JSON object mapping reporter IDs to
 * their default config maps for this profile.
 */
int32_t karandash_profile_reporter_configs(void) {
    return return_string(
        "{"
          "\"console\":{\"verbose\":true}"
        "}"
    );
}
