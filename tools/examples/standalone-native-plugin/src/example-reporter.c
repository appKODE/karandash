#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

/**
 * Standalone Native Reporter Plugin for Karandash
 *
 * Demonstrates the REPORTER module type in C.
 * Produces a simple plain-text validation report.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-reporter"
#define MODULE_NAME    "Example Native Reporter Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "REPORTER"
#define FORMAT_ID      "c-text"

/* Maximum findings the simple parser handles */
#define MAX_FINDINGS 256
/* Maximum length of a single field value */
#define MAX_FIELD_LEN 512

/* ============================================================================
 * Minimal JSON helpers
 * ============================================================================ */

/**
 * Extract the string value of the first occurrence of "key":"value" in json.
 * Writes at most (out_size - 1) bytes into out. Returns 1 on success, 0 on failure.
 */
static int json_string_field(const char* json, const char* key, char* out, size_t out_size) {
    char needle[128];
    snprintf(needle, sizeof(needle), "\"%s\"", key);

    const char* key_pos = strstr(json, needle);
    if (!key_pos) return 0;

    const char* colon = strchr(key_pos + strlen(needle), ':');
    if (!colon) return 0;

    const char* quote_open = strchr(colon + 1, '"');
    if (!quote_open) return 0;

    const char* src = quote_open + 1;
    size_t written = 0;

    while (*src && *src != '"' && written < out_size - 1) {
        if (*src == '\\' && *(src + 1)) {
            src++;
        }
        out[written++] = *src++;
    }
    out[written] = '\0';
    return 1;
}

/* ============================================================================
 * Finding parser - scans the JSON array for severity/rule/message objects
 * ============================================================================ */

typedef struct {
    char severity[64];
    char rule[128];
    char message[MAX_FIELD_LEN];
} Finding;

static int parse_findings(const char* findings_json, Finding* findings, int max_findings) {
    int count = 0;
    const char* p = findings_json;

    while (count < max_findings) {
        /* Find next object opening brace */
        const char* obj_start = strchr(p, '{');
        if (!obj_start) break;

        /* Find matching closing brace (naive - assumes no nested objects in findings) */
        const char* obj_end = strchr(obj_start, '}');
        if (!obj_end) break;

        /* Copy the object fragment into a temporary buffer for field extraction */
        size_t obj_len = (size_t)(obj_end - obj_start + 1);
        char* obj = (char*)malloc(obj_len + 1);
        if (!obj) break;
        strncpy(obj, obj_start, obj_len);
        obj[obj_len] = '\0';

        Finding* f = &findings[count];
        if (!json_string_field(obj, "severity", f->severity, sizeof(f->severity)))
            strncpy(f->severity, "INFO", sizeof(f->severity));
        if (!json_string_field(obj, "rule", f->rule, sizeof(f->rule)))
            strncpy(f->rule, "unknown", sizeof(f->rule));
        if (!json_string_field(obj, "message", f->message, sizeof(f->message)))
            strncpy(f->message, "", sizeof(f->message));

        free(obj);
        count++;
        p = obj_end + 1;
    }

    return count;
}

/* ============================================================================
 * Report builder
 * ============================================================================ */

static char* build_text_report(const Finding* findings, int count, int32_t passed) {
    /* Estimate buffer size: header + one line per finding */
    size_t buf_size = 512 + (size_t)count * (MAX_FIELD_LEN + 128);
    char* buf = (char*)malloc(buf_size);
    if (!buf) return strdup("Error: out of memory\n");

    int total = count + passed;
    int offset = 0;

    offset += snprintf(buf + offset, buf_size - (size_t)offset,
        "=== Karandash Validation Report (c-text) ===\n"
        "Rules checked : %d\n"
        "Passed        : %d\n"
        "Findings      : %d\n"
        "---------------------------------------------\n",
        total, passed, count);

    if (count == 0) {
        offset += snprintf(buf + offset, buf_size - (size_t)offset,
            "All rules passed.\n");
    } else {
        for (int i = 0; i < count; i++) {
            offset += snprintf(buf + offset, buf_size - (size_t)offset,
                "[%s] %s: %s\n",
                findings[i].severity,
                findings[i].rule,
                findings[i].message);
        }
    }

    offset += snprintf(buf + offset, buf_size - (size_t)offset,
        "=============================================\n");

    return buf;
}

/* ============================================================================
 * ABI exports
 * ============================================================================ */

int32_t karandash_abi_version(void) {
    return 1;
}

const char* karandash_module_id(void) {
    return strdup(MODULE_ID);
}

const char* karandash_module_name(void) {
    return strdup(MODULE_NAME);
}

const char* karandash_module_version(void) {
    return strdup(MODULE_VERSION);
}

const char* karandash_module_type(void) {
    return strdup(MODULE_TYPE);
}

int32_t karandash_initialize(const char* context_json) {
    (void)context_json;
    fprintf(stderr, "Example Native Reporter initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Reporter shutting down\n");
}

const char* karandash_format_id(void) {
    return strdup(FORMAT_ID);
}

const char* karandash_report(
    const char* findings_json,
    int32_t passed,
    const char* config_json
) {
    (void)config_json;

    if (!findings_json) {
        return strdup("=== Karandash Validation Report (c-text) ===\nNo data.\n");
    }

    Finding findings[MAX_FINDINGS];
    int count = parse_findings(findings_json, findings, MAX_FINDINGS);
    return build_text_report(findings, count, passed);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
