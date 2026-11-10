#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <string>

#define MODULE_ID      "cpp-standalone-plugin"
#define MODULE_NAME    "C++ Standalone Rule Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "RULE"

static char* to_heap(const std::string& s) { return strdup(s.c_str()); }

static std::string json_string(const std::string& json, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    auto kp = json.find(needle);
    if (kp == std::string::npos) return {};
    auto colon = json.find(':', kp + needle.size());
    if (colon == std::string::npos) return {};
    auto qo = json.find('"', colon + 1);
    if (qo == std::string::npos) return {};
    std::string val;
    for (auto i = qo + 1; i < json.size(); ++i) {
        if (json[i] == '\\' && i + 1 < json.size()) { ++i; continue; }
        if (json[i] == '"') break;
        val += json[i];
    }
    return val;
}

static bool has_conventional_prefix(const std::string& title) {
    const char* prefixes[] = {
        "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:"
    };
    for (const auto& p : prefixes) {
        if (title.rfind(p, 0) == 0) return true;
    }
    return false;
}

int32_t karandash_abi_version() { return 1; }

const char* karandash_module_id()      { return strdup(MODULE_ID); }
const char* karandash_module_name()    { return strdup(MODULE_NAME); }
const char* karandash_module_version() { return strdup(MODULE_VERSION); }
const char* karandash_module_type()    { return strdup(MODULE_TYPE); }

int32_t karandash_initialize(const char* /*context_json*/) { return 1; }
void    karandash_shutdown() {}

const char* karandash_contributed_rules() {
    return strdup("[\"cpp-title-check\"]");
}

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* /*config_json*/
) {
    if (!rule_id || !mr_json) return strdup("[]");
    if (std::string(rule_id) != "cpp-title-check") return strdup("[]");

    std::string title = json_string(std::string(mr_json), "title");
    if (!has_conventional_prefix(title)) {
        return to_heap(
            "[{\"rule\":\"cpp-title-check\","
            "\"message\":\"Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:\","
            "\"severity\":\"ERROR\","
            "\"location\":null}]"
        );
    }
    return strdup("[]");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}
