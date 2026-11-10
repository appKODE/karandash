#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native CLI Command Plugin for Karandash
 *
 * Demonstrates the CLI_COMMAND module type in C.
 * Registers a custom CLI command that the host exposes to users.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "example-native-cli-command"
#define MODULE_NAME    "Example Native CLI Command Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "CLI_COMMAND"

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
    fprintf(stderr, "Example Native CLI Command Plugin initialized\n");
    return 1;
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native CLI Command Plugin shutting down\n");
}

/**
 * Return a JSON array describing the CLI commands this plugin contributes.
 *
 * Each entry has:
 *   "name"        - the subcommand name users type
 *   "help"        - short description shown in --help output
 *   "subcommands" - nested subcommands (empty array if none)
 *
 * @return Heap-allocated JSON array string
 */
const char* karandash_cli_commands(void) {
    return strdup(
        "[{\"name\":\"example\","
        "\"help\":\"Example plugin command\","
        "\"subcommands\":[]}]"
    );
}

void karandash_free_string(const char* ptr) {
    if (ptr) free((void*)ptr);
}
