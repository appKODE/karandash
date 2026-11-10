#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native Profile Plugin for Karandash
 *
 * Demonstrates the PROFILE module type in C.
 * A profile bundles a set of pre-configured rule and reporter settings
 * that users can activate with a single config reference.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-profile"
#define MODULE_NAME    "Example Native Profile Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "PROFILE"

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
    fprintf(stderr, "Example Native Profile Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Profile Plugin shutting down\n");
}

/**
 * Return the unique profile identifier.
 * Users reference this ID in their config's "extends" or "profile" field.
 *
 * @return Heap-allocated profile ID string
 */
const char* karandash_profile_id(void) {
    return strdup("example-profile");
}

/**
 * Return a short human-readable description of what this profile enforces.
 *
 * @return Heap-allocated summary string
 */
const char* karandash_profile_summary(void) {
    return strdup("Example profile for testing");
}

/**
 * Return a JSON array of tags categorising this profile.
 *
 * @return Heap-allocated JSON array string
 */
const char* karandash_profile_tags(void) {
    return strdup("[\"example\",\"testing\"]");
}

/**
 * Return a JSON object of rule configurations this profile activates.
 * Keys match the rule IDs used in the "rules:" config block.
 *
 * @return Heap-allocated JSON object string
 */
const char* karandash_profile_rule_configs(void) {
    return strdup("{\"titleFormat\":{\"pattern\":\"^.*$\"}}");
}

/**
 * Return a JSON object of reporter configurations this profile activates.
 * Keys match the reporter IDs used in the "reporters:" config block.
 *
 * @return Heap-allocated JSON object string (empty object = no reporter overrides)
 */
const char* karandash_profile_reporter_configs(void) {
    return strdup("{}");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
