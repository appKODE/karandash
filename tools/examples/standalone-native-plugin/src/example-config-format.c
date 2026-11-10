#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native Config Format Plugin for Karandash
 *
 * Demonstrates the CONFIG_FORMAT module type in C.
 * Registers a custom configuration file format that the host can parse.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-config-format"
#define MODULE_NAME    "Example Native Config Format Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "CONFIG_FORMAT"

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
    fprintf(stderr, "Example Native Config Format Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Config Format Plugin shutting down\n");
}

/**
 * Return the format identifier for this config format.
 * The host uses this to match file extensions and route parsing.
 *
 * @return Heap-allocated format ID string
 */
const char* karandash_config_format_id(void) {
    return strdup("example-format");
}

/**
 * Return a JSON array of file extensions this format handles (without leading dot).
 *
 * @return Heap-allocated JSON array string
 */
const char* karandash_config_format_extensions(void) {
    return strdup("[\"exf\"]");
}

/**
 * Parse configuration file content into a JSON map.
 *
 * In a real implementation, parse the custom format and return a JSON object
 * matching the Karandash config schema.
 *
 * @param content - Raw file content as a UTF-8 string
 * @return Heap-allocated JSON object string representing the parsed config
 */
const char* karandash_config_parse(const char* content) {
    (void)content;
    return strdup("{\"parsed\":true}");
}

/**
 * Extract the "extends" base config path from file content, if present.
 *
 * @param content - Raw file content as a UTF-8 string
 * @return Heap-allocated string with the extends path, or "null" if none
 */
const char* karandash_config_parse_extends(const char* content) {
    (void)content;
    return strdup("null");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
