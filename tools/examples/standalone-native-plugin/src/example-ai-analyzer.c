#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Analyzer Plugin for Karandash
 *
 * Demonstrates the AI_ANALYZER module type in C.
 * An AI analyzer receives a merge request and produces a list of findings
 * by querying an AI model, without being tied to a specific rule ID.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-analyzer"
#define MODULE_NAME    "Example Native AI Analyzer Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_ANALYZER"

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
    fprintf(stderr, "Example Native AI Analyzer Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Analyzer Plugin shutting down\n");
}

/**
 * Analyse a merge request using an AI model and return findings.
 *
 * In a real implementation, build a prompt from mr_json, call the model
 * specified by the "model" parameter within the token budget, and parse
 * the response into Finding objects.
 *
 * @param mr_json      - JSON object containing merge request data
 * @param budget_json  - JSON object describing the token budget
 * @param model        - Model identifier string to use for analysis
 * @param config_json  - JSON object with analyzer-specific configuration
 * @return Heap-allocated JSON array of findings (empty array = no issues found)
 */
const char* karandash_ai_analyze(
    const char* mr_json,
    const char* budget_json,
    const char* model,
    const char* config_json
) {
    (void)mr_json;
    (void)budget_json;
    (void)model;
    (void)config_json;
    return strdup("[]");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
