#include <stdint.h>

/*
 * Karandash WASM Reporter Plugin - C implementation
 *
 * Reporter: wasm-c-text
 *   - Renders findings as a plain-text summary.
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-reporter-plugin"
#define MODULE_NAME    "WASM C Reporter Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "REPORTER"
#define FORMAT_ID      "wasm-c-text"

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

/* ── Reporter exports ───────────────────────────────────────────────────── */

int32_t karandash_format_id(void) {
    return return_string(FORMAT_ID);
}

/*
 * karandash_report - produce a plain-text report from findings JSON.
 *
 * findings_ptr/findings_len: JSON array of Finding objects from the host.
 * passed: non-zero when validation passed overall.
 * cfg_ptr/cfg_len: reporter config (unused by this simple reporter).
 *
 * Returns a length-prefixed string containing the plain-text report.
 */
int32_t karandash_report(
    int32_t findings_ptr, int32_t findings_len,
    int32_t passed,
    int32_t cfg_ptr,      int32_t cfg_len
) {
    (void)cfg_ptr;
    (void)cfg_len;

    const unsigned char *findings_buf = &heap[findings_ptr];

    /* Build a minimal text report without a JSON parser. */
    /* Count occurrences of "severity" keys to approximate finding count. */
    int32_t count = 0;
    for (int32_t i = 0; i < findings_len - 10; i++) {
        if (findings_buf[i]     == '"' &&
            findings_buf[i + 1] == 's' &&
            findings_buf[i + 2] == 'e' &&
            findings_buf[i + 3] == 'v' &&
            findings_buf[i + 4] == 'e' &&
            findings_buf[i + 5] == 'r' &&
            findings_buf[i + 6] == 'i' &&
            findings_buf[i + 7] == 't' &&
            findings_buf[i + 8] == 'y' &&
            findings_buf[i + 9] == '"') {
            count++;
        }
    }

    const char *status = (passed != 0) ? "PASSED" : "FAILED";

    /* Compose report into heap buffer directly. */
    char header[] = "Karandash WASM C Report\n";
    char sep[]    = "========================\n";
    char status_prefix[] = "Status:   ";
    char newline[] = "\n";
    char findings_prefix[] = "Findings: ";

    /* Write header + separator + status + finding count as raw bytes */
    int32_t out_ptr = heap_offset + 4; /* reserve 4 bytes for length prefix */
    int32_t start   = out_ptr;

    unsigned char *p = &heap[out_ptr];

    /* Copy helper: writes str into heap at p, advances p */
#define WRITE(s) do { \
    const char *_s = (s); \
    while (*_s) { *p++ = (unsigned char)*_s++; } \
} while (0)

    WRITE(header);
    WRITE(sep);
    WRITE(status_prefix);
    WRITE(status);
    WRITE(newline);
    WRITE(findings_prefix);

    /* Write count digit(s) */
    if (count == 0) {
        *p++ = '0';
    } else {
        char digits[12];
        int32_t d = 0;
        int32_t n = count;
        while (n > 0) { digits[d++] = (char)('0' + (n % 10)); n /= 10; }
        for (int32_t k = d - 1; k >= 0; k--) *p++ = (unsigned char)digits[k];
    }
    WRITE(newline);

#undef WRITE

    int32_t out_len = (int32_t)(p - &heap[start]);
    heap_offset = start + out_len;

    /* Write length prefix into the 4 reserved bytes */
    uint32_t ulen = (uint32_t)out_len;
    heap[out_ptr - 4] = (unsigned char)(ulen & 0xFF);
    heap[out_ptr - 3] = (unsigned char)((ulen >> 8)  & 0xFF);
    heap[out_ptr - 2] = (unsigned char)((ulen >> 16) & 0xFF);
    heap[out_ptr - 1] = (unsigned char)((ulen >> 24) & 0xFF);

    return out_ptr - 4;
}
