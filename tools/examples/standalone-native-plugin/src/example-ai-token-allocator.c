#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native AI Token Allocator Plugin for Karandash
 *
 * Demonstrates the AI_TOKEN_ALLOCATOR module type in C.
 * A token allocator decides how a fixed token budget is distributed across
 * multiple AI rules, enabling priority-based or adaptive allocation strategies.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-ai-token-allocator"
#define MODULE_NAME    "Example Native AI Token Allocator Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_TOKEN_ALLOCATOR"

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
    fprintf(stderr, "Example Native AI Token Allocator Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native AI Token Allocator Plugin shutting down\n");
}

/**
 * Allocate a token budget across a set of AI rules.
 *
 * The host calls this before running AI rules so each rule knows its budget.
 * Returning an empty object delegates allocation to the host's default equal-split
 * strategy. A real implementation would parse rules_json and priorities_json to
 * return a map of rule ID -> token count.
 *
 * @param rules_json      - JSON array of rule IDs that need token budgets
 * @param total_budget    - Total number of tokens available for all rules
 * @param priorities_json - JSON object mapping rule ID to priority weight
 * @return Heap-allocated JSON object mapping rule ID -> allocated token count,
 *         or empty object "{}" to use the host's default equal-split strategy
 */
const char* karandash_ai_allocate_tokens(
    const char* rules_json,
    int32_t total_budget,
    const char* priorities_json
) {
    (void)rules_json;
    (void)total_budget;
    (void)priorities_json;
    return strdup("{}");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
