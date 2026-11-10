#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Provider Plugin for Karandash
 *
 * Demonstrates the AI_PROVIDER module type in C.
 * An AI provider fulfils completion requests sent by the AI rule module,
 * acting as a bridge to a language model backend.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-provider"
#define MODULE_NAME    "Example Native AI Provider Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_PROVIDER"

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
    fprintf(stderr, "Example Native AI Provider Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Provider Plugin shutting down\n");
}

/**
 * Perform an AI completion request.
 *
 * In a real implementation, deserialise request_json (which contains the
 * model, messages, temperature, and other parameters), call the remote LLM
 * API, and return the response as a JSON object.
 *
 * @param request_json - JSON object describing the completion request
 * @return Heap-allocated JSON object string with the completion response:
 *         { "content": "...", "promptTokens": N, "completionTokens": N,
 *           "totalTokens": N, "model": "..." }
 */
const char* karandash_ai_complete(const char* request_json) {
    (void)request_json;
    return strdup(
        "{\"content\":\"Example AI response\","
        "\"promptTokens\":10,"
        "\"completionTokens\":20,"
        "\"totalTokens\":30,"
        "\"model\":\"example-model\"}"
    );
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
