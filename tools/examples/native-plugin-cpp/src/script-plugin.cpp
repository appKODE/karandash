#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <string>

#define MODULE_ID      "cpp-script-example"
#define MODULE_NAME    "C++ Script Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "SCRIPT"

// ---------------------------------------------------------------------------
// Heap helper
// ---------------------------------------------------------------------------

static char* to_heap(const std::string& s) {
    return strdup(s.c_str());
}

// ---------------------------------------------------------------------------
// ABI exports
// ---------------------------------------------------------------------------

int32_t karandash_abi_version() {
    return 1;
}

const char* karandash_module_id() {
    return strdup(MODULE_ID);
}

const char* karandash_module_name() {
    return strdup(MODULE_NAME);
}

const char* karandash_module_version() {
    return strdup(MODULE_VERSION);
}

const char* karandash_module_type() {
    return strdup(MODULE_TYPE);
}

int32_t karandash_initialize(const char* /*context_json*/) {
    return 1;
}

void karandash_shutdown() {}

/**
 * Execute a script against a merge request.
 *
 * Script plugins run arbitrary logic against the MR and return findings
 * directly. Unlike rule plugins, they are not bound to a specific rule ID.
 *
 * @param scripts_json  JSON array of script definitions from config
 * @param mr_json       JSON string containing merge request data
 * @param context_json  JSON string containing execution context
 * @return              Heap-allocated JSON array of findings; caller must free
 *                      via karandash_free_string
 */
const char* karandash_execute(
    const char* /*scripts_json*/,
    const char* /*mr_json*/,
    const char* /*context_json*/
) {
    const std::string result =
        "[{\"rule\":\"cpp-script\","
        "\"message\":\"Script executed successfully\","
        "\"severity\":\"INFO\","
        "\"location\":null}]";
    return to_heap(result);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}
