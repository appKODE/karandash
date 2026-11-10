#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Interceptor Plugin for Karandash
 *
 * Demonstrates the AI_INTERCEPTOR module type in C.
 * An AI interceptor sits in the completion pipeline and can inspect,
 * modify, short-circuit, or log requests before they reach the provider.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-interceptor"
#define MODULE_NAME    "Example Native AI Interceptor Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_INTERCEPTOR"

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
    fprintf(stderr, "Example Native AI Interceptor Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Interceptor Plugin shutting down\n");
}

/**
 * Intercept an AI completion request.
 *
 * Return {"passthrough": true} to forward the request unchanged to the next
 * interceptor or the AI provider. Return a full completion response object to
 * short-circuit the chain (e.g., serve from cache).
 *
 * @param request_json - JSON object describing the completion request
 * @param context_json - JSON object with additional pipeline context
 * @return Heap-allocated JSON object: {"passthrough":true} or a full response
 */
const char* karandash_ai_intercept(const char* request_json, const char* context_json) {
    (void)request_json;
    (void)context_json;
    return strdup("{\"passthrough\":true}");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
