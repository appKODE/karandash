#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Prompt Processor Plugin for Karandash
 *
 * Demonstrates the AI_PROMPT_PROCESSOR module type in C.
 * A prompt processor transforms prompts before they are sent to an AI provider,
 * enabling injection of context, sanitisation, or template expansion.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-prompt-processor"
#define MODULE_NAME    "Example Native AI Prompt Processor Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_PROMPT_PROCESSOR"

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
    fprintf(stderr, "Example Native AI Prompt Processor Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Prompt Processor Plugin shutting down\n");
}

/**
 * Transform the user prompt before it is sent to the AI provider.
 *
 * This passthrough implementation returns the prompt unchanged.
 * A real implementation could append context, enforce length limits, or
 * apply templating.
 *
 * @param prompt       - The user prompt string
 * @param context_json - JSON object with pipeline context
 * @return Heap-allocated transformed prompt string
 */
const char* karandash_ai_process_prompt(const char* prompt, const char* context_json) {
    (void)context_json;
    if (prompt) return strdup(prompt);
    return strdup("");
}

/**
 * Transform the system prompt before it is sent to the AI provider.
 *
 * This passthrough implementation returns the system prompt unchanged.
 *
 * @param system_prompt - The system prompt string (may be NULL)
 * @param context_json  - JSON object with pipeline context
 * @return Heap-allocated transformed system prompt string
 */
const char* karandash_ai_process_system_prompt(const char* system_prompt, const char* context_json) {
    (void)context_json;
    if (system_prompt) return strdup(system_prompt);
    return strdup("");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
