#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <cstdint>

#define MODULE_ID      "cpp-ai-token-allocator-example"
#define MODULE_NAME    "C++ AI Token Allocator Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_TOKEN_ALLOCATOR"

extern "C" {

int32_t karandash_abi_version() { return 1; }
const char* karandash_module_id() { return strdup(MODULE_ID); }
const char* karandash_module_name() { return strdup(MODULE_NAME); }
const char* karandash_module_version() { return strdup(MODULE_VERSION); }
const char* karandash_module_type() { return strdup(MODULE_TYPE); }
int32_t karandash_initialize(const char* /*context_json*/) { return 1; }
void karandash_shutdown() {}
void karandash_free_string(const char* p) { if (p) free(const_cast<char*>(p)); }

const char* karandash_ai_allocate_tokens(
    const char* /*rules_json*/,
    int32_t     /*total_budget*/,
    const char* /*priorities_json*/
) {
    return strdup("{}");
}

} // extern "C"
