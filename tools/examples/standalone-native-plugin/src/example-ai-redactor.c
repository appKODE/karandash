#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Redactor Plugin for Karandash
 *
 * Demonstrates the AI_REDACTOR module type in C.
 * A redactor scrubs sensitive data (tokens, passwords, PII) from text before
 * it is sent to an external AI provider, preventing secret leakage.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-redactor"
#define MODULE_NAME    "Example Native AI Redactor Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_REDACTOR"

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
    fprintf(stderr, "Example Native AI Redactor Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Redactor Plugin shutting down\n");
}

/**
 * Redact sensitive content from text before it is sent to an AI provider.
 *
 * This passthrough implementation returns the text unchanged.
 * A real implementation would apply regex patterns or entropy-based detection
 * to replace secrets with placeholder tokens (e.g., "[REDACTED]").
 *
 * @param text         - The input text to inspect and redact
 * @param context_json - JSON object with redaction configuration and context
 * @return Heap-allocated redacted text string
 */
const char* karandash_ai_redact(const char* text, const char* context_json) {
    (void)context_json;
    if (text) return strdup(text);
    return strdup("");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
