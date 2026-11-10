#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

/**
 * Standalone Native MR Mutator Plugin for Karandash
 *
 * Demonstrates the MERGE_REQUEST_MUTATOR module type in C.
 * Uses in-memory state; all mutations succeed and are logged to stderr.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-mutator"
#define MODULE_NAME    "Example Native MR Mutator Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "MERGE_REQUEST_MUTATOR"

/* In-memory draft state - keyed by a simple flag for the demo */
static int g_draft_state = 0;

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
    fprintf(stderr, "Example Native Mutator initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Mutator shutting down\n");
}

int32_t karandash_add_labels(
    const char* project_id,
    const char* mr_id,
    const char* labels_json
) {
    fprintf(stderr, "Example Native Mutator: add_labels project=%s mr=%s labels=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            labels_json ? labels_json : "[]");
    return 1;
}

int32_t karandash_remove_labels(
    const char* project_id,
    const char* mr_id,
    const char* labels_json
) {
    fprintf(stderr, "Example Native Mutator: remove_labels project=%s mr=%s labels=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            labels_json ? labels_json : "[]");
    return 1;
}

int32_t karandash_set_draft(
    const char* project_id,
    const char* mr_id,
    int32_t draft
) {
    fprintf(stderr, "Example Native Mutator: set_draft project=%s mr=%s draft=%d\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            draft);
    g_draft_state = draft;
    return 1;
}

int32_t karandash_update_title(
    const char* project_id,
    const char* mr_id,
    const char* title
) {
    fprintf(stderr, "Example Native Mutator: update_title project=%s mr=%s title=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            title ? title : "(null)");
    return 1;
}

int32_t karandash_update_description(
    const char* project_id,
    const char* mr_id,
    const char* description
) {
    fprintf(stderr, "Example Native Mutator: update_description project=%s mr=%s description=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            description ? description : "(null)");
    return 1;
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
