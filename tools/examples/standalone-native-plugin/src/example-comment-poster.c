#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

/**
 * Standalone Native Comment Poster Plugin for Karandash
 *
 * Demonstrates the COMMENT_POSTER module type in C.
 * Uses an in-memory comment store; all operations log to stderr.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-comment-poster"
#define MODULE_NAME    "Example Native Comment Poster Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "COMMENT_POSTER"

/* Simple in-memory comment counter for generating mock IDs */
static int g_next_comment_id = 1000;

/* Marker used to identify managed comments for find_existing_comment */
#define KARANDASH_MARKER "<!-- karandash -->"

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
    fprintf(stderr, "Example Native Comment Poster initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Comment Poster shutting down\n");
}

const char* karandash_post_comment(
    const char* project_id,
    const char* mr_id,
    const char* body
) {
    int comment_id = g_next_comment_id++;
    fprintf(stderr, "Example Native Comment Poster: post_comment project=%s mr=%s id=%d\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            comment_id);
    (void)body;

    char buf[32];
    snprintf(buf, sizeof(buf), "%d", comment_id);
    return strdup(buf);
}

int32_t karandash_update_comment(
    const char* project_id,
    const char* mr_id,
    const char* comment_id,
    const char* body
) {
    fprintf(stderr, "Example Native Comment Poster: update_comment project=%s mr=%s comment=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            comment_id ? comment_id : "(null)");
    (void)body;
    return 1;
}

const char* karandash_find_existing_comment(
    const char* project_id,
    const char* mr_id,
    const char* marker
) {
    fprintf(stderr, "Example Native Comment Poster: find_existing_comment project=%s mr=%s marker=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            marker ? marker : "(null)");
    /* No persistent store - always return NULL to indicate no existing comment */
    return NULL;
}

const char* karandash_post_inline_comment(
    const char* project_id,
    const char* mr_id,
    const char* body,
    const char* file_path,
    int32_t line,
    const char* diff_refs_json,
    int32_t start_line
) {
    int comment_id = g_next_comment_id++;
    fprintf(stderr,
            "Example Native Comment Poster: post_inline_comment project=%s mr=%s "
            "file=%s line=%d id=%d\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            file_path ? file_path : "(null)",
            line,
            comment_id);
    (void)body;
    (void)diff_refs_json;
    (void)start_line;

    char buf[32];
    snprintf(buf, sizeof(buf), "%d", comment_id);
    return strdup(buf);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
