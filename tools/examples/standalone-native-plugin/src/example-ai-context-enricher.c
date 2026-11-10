#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Context Enricher Plugin for Karandash
 *
 * Demonstrates the AI_CONTEXT_ENRICHER module type in C.
 * A context enricher augments the data map passed to the AI prompt template,
 * allowing plugins to inject external information (e.g., Jira tickets, CODEOWNERS).
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-context-enricher"
#define MODULE_NAME    "Example Native AI Context Enricher Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_CONTEXT_ENRICHER"

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
    fprintf(stderr, "Example Native AI Context Enricher Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Context Enricher Plugin shutting down\n");
}

/**
 * Enrich the context map used when rendering AI prompt templates.
 *
 * The returned JSON object is merged into the prompt context so that
 * template variables referencing the returned keys are resolved.
 *
 * @param mr_json      - JSON object containing merge request data
 * @param context_json - JSON object with the current accumulated context
 * @return Heap-allocated JSON object with additional key/value pairs to merge
 */
const char* karandash_ai_enrich_context(const char* mr_json, const char* context_json) {
    (void)mr_json;
    (void)context_json;
    return strdup("{\"example_key\":\"example_value\"}");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
