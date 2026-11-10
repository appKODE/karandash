#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native Script Plugin for Karandash
 *
 * This is a complete, self-contained example of a Karandash script plugin
 * written in C. It demonstrates:
 * - Native plugin ABI v1 implementation for SCRIPT type
 * - All required function exports
 * - karandash_execute entry point (instead of karandash_validate)
 * - Memory management with strdup/free
 *
 * Build: make
 * Install: cp libnative-example-script.{so,dylib,dll} ~/.karandash/plugins/
 */

/* Module metadata */
#define MODULE_ID      "native-script-example"
#define MODULE_NAME    "Example Native Script Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "SCRIPT"

/* ABI version - must match Karandash's expected version */
int32_t karandash_abi_version(void) {
    return 1;
}

/* Module identity functions - return heap-allocated strings */

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

/* Lifecycle functions */

int32_t karandash_initialize(const char* context_json) {
    (void)context_json;
    fprintf(stderr, "Example Native Script Plugin initialized\n");
    return 1;  /* Return 1 for success, 0 for failure */
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Script Plugin shutting down\n");
}

/**
 * Execute a script against a merge request
 *
 * Script plugins run arbitrary logic against the MR and return findings.
 * Unlike rule plugins, scripts are not bound to a specific rule ID -
 * they receive the full script configuration and produce findings directly.
 *
 * @param scripts_json  - JSON array of script definitions from config
 * @param mr_json       - JSON string containing merge request data
 * @param context_json  - JSON string containing execution context
 * @return JSON array of findings (heap-allocated, caller must free via karandash_free_string)
 */
const char* karandash_execute(
    const char* scripts_json,
    const char* mr_json,
    const char* context_json
) {
    (void)scripts_json;
    (void)mr_json;
    (void)context_json;

    return strdup(
        "[{\"rule\":\"native-script\","
        "\"message\":\"Script executed successfully\","
        "\"severity\":\"INFO\","
        "\"location\":null}]"
    );
}

/* Memory management */

void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
