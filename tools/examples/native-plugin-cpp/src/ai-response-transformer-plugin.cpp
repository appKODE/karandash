#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>

#define MODULE_ID      "cpp-ai-response-transformer-example"
#define MODULE_NAME    "C++ AI Response Transformer Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "AI_RESPONSE_TRANSFORMER"

extern "C" {

int32_t karandash_abi_version() { return 1; }
const char* karandash_module_id() { return strdup(MODULE_ID); }
const char* karandash_module_name() { return strdup(MODULE_NAME); }
const char* karandash_module_version() { return strdup(MODULE_VERSION); }
const char* karandash_module_type() { return strdup(MODULE_TYPE); }
int32_t karandash_initialize(const char* /*context_json*/) { return 1; }
void karandash_shutdown() {}
void karandash_free_string(const char* p) { if (p) free(const_cast<char*>(p)); }

const char* karandash_ai_transform_response(
    const char* response_json,
    const char* /*request_json*/,
    const char* /*context_json*/
) {
    return strdup(response_json ? response_json : "{}");
}

} // extern "C"
