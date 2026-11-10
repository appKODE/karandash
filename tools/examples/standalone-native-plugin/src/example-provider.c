#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

/**
 * Standalone Native MR Provider Plugin for Karandash
 *
 * Demonstrates the MERGE_REQUEST_PROVIDER module type in C.
 * Returns mock merge request JSON for any project/MR ID or URL.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-provider"
#define MODULE_NAME    "Example Native MR Provider Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "MERGE_REQUEST_PROVIDER"

/* Shared mock MR JSON returned by both fetch functions */
static const char* MOCK_MR_JSON =
    "{"
      "\"title\":\"Mock MR\","
      "\"description\":\"This is a mock merge request returned by the example native provider.\","
      "\"state\":\"opened\","
      "\"sourceBranch\":\"feature/mock-branch\","
      "\"targetBranch\":\"main\","
      "\"author\":{\"username\":\"mock\",\"name\":\"Mock User\"},"
      "\"labels\":[],"
      "\"commits\":["
        "{\"sha\":\"abc1234\",\"message\":\"feat: add mock feature\",\"author\":\"mock\"}"
      "],"
      "\"changedFiles\":["
        "{\"path\":\"src/mock.c\",\"changeType\":\"ADDED\",\"additions\":42,\"deletions\":0}"
      "],"
      "\"comments\":[],"
      "\"approvals\":[]"
    "}";

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
    fprintf(stderr, "Example Native Provider initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Provider shutting down\n");
}

const char* karandash_fetch_merge_request(const char* project_id, const char* mr_id) {
    fprintf(stderr, "Example Native Provider: fetching MR %s from project %s\n",
            mr_id ? mr_id : "(null)",
            project_id ? project_id : "(null)");
    return strdup(MOCK_MR_JSON);
}

const char* karandash_fetch_merge_request_by_url(const char* url) {
    fprintf(stderr, "Example Native Provider: fetching MR by URL %s\n",
            url ? url : "(null)");
    return strdup(MOCK_MR_JSON);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
