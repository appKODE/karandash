#!/usr/bin/env python3
"""
Karandash standalone Python plugin generator.

This script generates a C source file that implements a single rule plugin,
then the build.sh script compiles it into a shared library.

Rule: python-title-check
  Title must start with a conventional commit prefix:
  feat:, fix:, chore:, refactor:, docs:, test:, or ci:
"""

C_SOURCE = r"""
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define MODULE_ID      "python-standalone-plugin"
#define MODULE_NAME    "Python Standalone Rule Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "RULE"

/* strdup is POSIX, not C11: under -std=c11 it is undeclared and its pointer gets truncated to int. */
static char *heap_strdup(const char *s) {
    size_t n = strlen(s) + 1;
    char *copy = malloc(n);
    if (copy) memcpy(copy, s, n);
    return copy;
}

static const char *PREFIXES[] = {
    "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:", NULL
};

static int has_conventional_prefix(const char *title) {
    for (int i = 0; PREFIXES[i]; i++) {
        size_t plen = strlen(PREFIXES[i]);
        if (strncmp(title, PREFIXES[i], plen) == 0) return 1;
    }
    return 0;
}

static char *json_extract_title(const char *json) {
    const char *key = "\"title\":";
    const char *kp = strstr(json, key);
    if (!kp) return NULL;
    const char *p = kp + strlen(key);
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return NULL;
    p++;
    const char *end = strchr(p, '"');
    if (!end) return NULL;
    size_t len = (size_t)(end - p);
    char *title = (char *)malloc(len + 1);
    if (!title) return NULL;
    memcpy(title, p, len);
    title[len] = '\0';
    return title;
}

int32_t karandash_abi_version(void) { return 1; }
const char *karandash_module_id(void)      { return heap_strdup(MODULE_ID); }
const char *karandash_module_name(void)    { return heap_strdup(MODULE_NAME); }
const char *karandash_module_version(void) { return heap_strdup(MODULE_VERSION); }
const char *karandash_module_type(void)    { return heap_strdup(MODULE_TYPE); }
int32_t karandash_initialize(const char *ctx) { (void)ctx; return 1; }
void karandash_shutdown(void) {}
const char *karandash_contributed_rules(void) {
    return heap_strdup("[\"python-title-check\"]");
}

const char *karandash_validate(
    const char *rule_id, const char *mr_json, const char *config_json
) {
    (void)config_json;
    if (!rule_id || strcmp(rule_id, "python-title-check") != 0)
        return heap_strdup("[]");

    char *title = json_extract_title(mr_json ? mr_json : "");
    if (!title) {
        return heap_strdup(
            "[{\"rule\":\"python-title-check\","
            "\"severity\":\"ERROR\","
            "\"message\":\"Could not read MR title\","
            "\"location\":null}]"
        );
    }

    int ok = has_conventional_prefix(title);
    free(title);

    if (!ok) {
        return heap_strdup(
            "[{\"rule\":\"python-title-check\","
            "\"severity\":\"ERROR\","
            "\"message\":\"Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:\","
            "\"location\":null}]"
        );
    }
    return heap_strdup("[]");
}

void karandash_free_string(const char *ptr) {
    if (ptr) free((void *)ptr);
}
"""

import os

output_path = os.path.join(os.path.dirname(__file__), "generated_plugin.c")
with open(output_path, "w") as f:
    f.write(C_SOURCE.lstrip())

print(f"Generated: {output_path}")
