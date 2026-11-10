#include <stddef.h>
#include <stdint.h>

/*
 * Karandash WASM Plugin - C standalone implementation
 *
 * One guest serving several module types: karandash_describe lists RULE (the primary,
 * also returned by karandash_module_type), REPORTER, PROFILE and CLI_COMMAND, and the
 * host registers one part per type under the same id.
 *
 * Rule: wasm-c-standalone-check
 *   - Title must start with a conventional commit prefix:
 *     feat:, fix:, chore:, refactor:, docs:, test:, or ci:
 * Reporter: wasm-c-standalone-report - one summary line
 * Profile:  wasm-c-standalone-profile - enables the rule
 * Command:  wasm-c-standalone - listed in `karandash --help`
 *
 * WASM ABI v1: strings returned to the host are 4-byte LE length-prefixed.
 * Strings received from the host arrive as (ptr, len) - two i32 args.
 * No WASI, no libc: uses a bump allocator for all heap needs.
 */

#define MODULE_ID      "wasm-c-standalone-plugin"
#define MODULE_NAME    "WASM C Standalone Rule Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "RULE"
#define RULE_ID        "wasm-c-standalone-check"
#define FORMAT_ID      "wasm-c-standalone-report"
#define PROFILE_ID     "wasm-c-standalone-profile"

/* ── Bump allocator ─────────────────────────────────────────────────────── */

/*
 * The host reads and writes linear memory by absolute address, so every pointer crossing the ABI
 * is the address of a byte in `heap` (ADDR), not an offset into it.
 */
#define ADDR(p) ((int32_t)(size_t)(p))
#define PTR(a)  ((unsigned char *)(size_t)(a))

static unsigned char heap[65536];
static int32_t heap_offset = 0;

int32_t karandash_alloc(int32_t size) {
    int32_t ptr = ADDR(&heap[heap_offset]);
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
    unsigned char *buf = &heap[heap_offset];
    heap_offset       += (int32_t)total;

    buf[0] = (unsigned char)(len & 0xFF);
    buf[1] = (unsigned char)((len >> 8)  & 0xFF);
    buf[2] = (unsigned char)((len >> 16) & 0xFF);
    buf[3] = (unsigned char)((len >> 24) & 0xFF);
    for (uint32_t i = 0; i < len; i++) {
        buf[4 + i] = (unsigned char)str[i];
    }
    return ADDR(buf);
}

/* ── ABI: required exports ───────────────────────────────────────────────── */

int32_t karandash_abi_version(void) { return 1; }

int32_t karandash_module_id(void)      { return return_string(MODULE_ID); }
int32_t karandash_module_name(void)    { return return_string(MODULE_NAME); }
int32_t karandash_module_version(void) { return return_string(MODULE_VERSION); }
int32_t karandash_module_type(void)    { return return_string(MODULE_TYPE); }

/* Optional: SDK properties in one call; absent key = SDK default. */
int32_t karandash_describe(void) {
    return return_string(
        "{\"id\":\"" MODULE_ID "\",\"types\":[\"" MODULE_TYPE "\",\"REPORTER\",\"PROFILE\",\"CLI_COMMAND\"],"
        "\"properties\":{\"defaultEnabled\":true,\"supportedEnrichment\":[]}}"
    );
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

/* ── Prefix check ───────────────────────────────────────────────────────── */

static const char* prefixes[] = {
    "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:", 0
};

static int starts_with(const unsigned char *buf, int32_t buf_len, const char *prefix) {
    int32_t i = 0;
    while (prefix[i]) {
        if (i >= buf_len || buf[i] != (unsigned char)prefix[i]) return 0;
        i++;
    }
    return 1;
}

static int has_conventional_prefix(const unsigned char *buf, int32_t len) {
    for (int i = 0; prefixes[i]; i++) {
        if (starts_with(buf, len, prefixes[i])) return 1;
    }
    return 0;
}

/* ── Title extraction ───────────────────────────────────────────────────── */

static int extract_title(
    const unsigned char *buf, int32_t buf_len,
    const unsigned char **out_start, int32_t *out_len
) {
    const char *needle = "\"title\":";
    int32_t needle_len = 8;
    int32_t i = 0;

    while (i <= buf_len - needle_len) {
        int match = 1;
        for (int32_t j = 0; j < needle_len; j++) {
            if (buf[i + j] != (unsigned char)needle[j]) { match = 0; break; }
        }
        if (match) {
            int32_t pos = i + needle_len;
            while (pos < buf_len && buf[pos] == ' ') pos++;
            if (pos >= buf_len || buf[pos] != '"') return 0;
            pos++;
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

/* ── Validation ─────────────────────────────────────────────────────────── */

int32_t karandash_validate(
    int32_t rule_id_ptr, int32_t rule_id_len,
    int32_t mr_ptr,      int32_t mr_len,
    int32_t cfg_ptr,     int32_t cfg_len
) {
    (void)cfg_ptr;
    (void)cfg_len;

    const unsigned char *rule_id = PTR(rule_id_ptr);
    const char *expected = RULE_ID;
    int32_t expected_len = 0;
    while (expected[expected_len]) expected_len++;

    if (rule_id_len != expected_len) return return_string("[]");
    for (int32_t i = 0; i < rule_id_len; i++) {
        if (rule_id[i] != (unsigned char)expected[i]) return return_string("[]");
    }

    const unsigned char *mr_buf = PTR(mr_ptr);
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

    if (!has_conventional_prefix(title_start, title_len)) {
        return return_string(
            "[{\"rule\":\"" RULE_ID "\","
            "\"severity\":\"ERROR\","
            "\"message\":\"Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:\","
            "\"location\":null}]"
        );
    }

    return return_string("[]");
}

/* ── Reporter ───────────────────────────────────────────────────────────── */

int32_t karandash_format_id(void) { return return_string(FORMAT_ID); }

int32_t karandash_report(
    int32_t findings_ptr, int32_t findings_len,
    int32_t passed,
    int32_t cfg_ptr,      int32_t cfg_len
) {
    (void)findings_ptr;
    (void)findings_len;
    (void)cfg_ptr;
    (void)cfg_len;
    return return_string(passed ? "WASM-C-STANDALONE-REPORT passed" : "WASM-C-STANDALONE-REPORT failed");
}

/* ── Profile ────────────────────────────────────────────────────────────── */

int32_t karandash_profile_id(void)      { return return_string(PROFILE_ID); }
int32_t karandash_profile_summary(void) { return return_string("Profile served by the standalone WASM C example"); }
int32_t karandash_profile_tags(void)    { return return_string("[\"wasm\",\"composite\"]"); }

int32_t karandash_profile_rule_configs(void) {
    return return_string("{\"" RULE_ID "\":{\"enabled\":true}}");
}

int32_t karandash_profile_reporter_configs(void) { return return_string("{}"); }

/* ── CLI command ────────────────────────────────────────────────────────── */

int32_t karandash_cli_commands(void) {
    return return_string(
        "[{\"name\":\"wasm-c-standalone\",\"help\":\"Command served by the standalone WASM C example\"}]"
    );
}

/* Optional: makes the listed command runnable. `command` is its path, `args_json` a JSON array of the remaining
 * arguments; answers {"exitCode":n,"stdout":[lines],"stderr":[lines]}. */
int32_t karandash_run_cli_command(
    int32_t command_ptr, int32_t command_len,
    int32_t args_ptr,    int32_t args_len
) {
    (void)command_ptr;
    (void)command_len;
    (void)args_ptr;
    (void)args_len;
    return return_string("{\"exitCode\":0,\"stdout\":[\"WASM-C-STANDALONE-RAN\"],\"stderr\":[]}");
}
