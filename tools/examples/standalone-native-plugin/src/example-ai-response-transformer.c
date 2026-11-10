#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Response Transformer Plugin for Karandash
 *
 * Demonstrates the AI_RESPONSE_TRANSFORMER module type in C.
 * A response transformer post-processes the raw AI provider response before
 * the host parses it into findings, enabling normalisation or enrichment.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-response-transformer"
#define MODULE_NAME    "Example Native AI Response Transformer Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_RESPONSE_TRANSFORMER"

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
    fprintf(stderr, "Example Native AI Response Transformer Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Response Transformer Plugin shutting down\n");
}

/**
 * Transform the raw AI provider response JSON before the host parses it.
 *
 * This passthrough implementation returns the response unchanged.
 * A real implementation could strip markdown fences, fix malformed JSON,
 * or re-map provider-specific fields to the expected schema.
 *
 * @param response_json - JSON object returned by the AI provider
 * @param request_json  - The original completion request JSON (for context)
 * @param context_json  - JSON object with additional pipeline context
 * @return Heap-allocated (possibly transformed) JSON response string
 */
const char* karandash_ai_transform_response(
    const char* response_json,
    const char* request_json,
    const char* context_json
) {
    (void)request_json;
    (void)context_json;
    if (response_json) return strdup(response_json);
    return strdup("{}");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
