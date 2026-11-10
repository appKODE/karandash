#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <sstream>
#include <cctype>

#define MODULE_ID      "cpp-rule-example"
#define MODULE_NAME    "C++ Rule Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "RULE"

static constexpr int DEFAULT_MAX_FILES = 20;

// ---------------------------------------------------------------------------
// Minimal JSON extraction helpers
// ---------------------------------------------------------------------------

/**
 * Extract the string value of a top-level JSON key.
 * Returns empty string if key is not found or value is not a string.
 */
static std::string json_string(const std::string& json, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    auto key_pos = json.find(needle);
    if (key_pos == std::string::npos) return {};

    auto colon = json.find(':', key_pos + needle.size());
    if (colon == std::string::npos) return {};

    auto quote_open = json.find('"', colon + 1);
    if (quote_open == std::string::npos) return {};

    std::string value;
    for (auto i = quote_open + 1; i < json.size(); ++i) {
        if (json[i] == '\\' && i + 1 < json.size()) {
            ++i;
            continue;
        }
        if (json[i] == '"') break;
        value += json[i];
    }
    return value;
}

/**
 * Extract the integer value of a top-level JSON key.
 * Returns default_val if key is not found or value is not a number.
 */
static int json_int(const std::string& json, const std::string& key, int default_val) {
    const std::string needle = "\"" + key + "\"";
    auto key_pos = json.find(needle);
    if (key_pos == std::string::npos) return default_val;

    auto colon = json.find(':', key_pos + needle.size());
    if (colon == std::string::npos) return default_val;

    auto num_start = json.find_first_not_of(" \t\r\n", colon + 1);
    if (num_start == std::string::npos) return default_val;

    if (!std::isdigit(static_cast<unsigned char>(json[num_start])) && json[num_start] != '-') {
        return default_val;
    }

    return std::stoi(json.substr(num_start));
}

/**
 * Count the number of elements in a JSON array at the given top-level key.
 * Uses a simple depth-tracking scan - accurate for non-nested object arrays.
 */
static int json_array_count(const std::string& json, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    auto key_pos = json.find(needle);
    if (key_pos == std::string::npos) return 0;

    auto colon = json.find(':', key_pos + needle.size());
    if (colon == std::string::npos) return 0;

    auto bracket = json.find('[', colon + 1);
    if (bracket == std::string::npos) return 0;

    int depth = 0;
    int count = 0;
    bool inside = false;

    for (auto i = bracket; i < json.size(); ++i) {
        char c = json[i];
        if (c == '[' || c == '{') {
            ++depth;
            if (depth == 1) inside = true;
        } else if (c == ']' || c == '}') {
            --depth;
            if (depth == 0) break;
        } else if (c == ',' && depth == 1) {
            ++count;
        }
    }

    if (inside && depth == 0 && count == 0) {
        // Check if array is non-empty by looking for any non-whitespace between [ and ]
        auto inner_start = bracket + 1;
        auto inner_end = json.find(']', inner_start);
        if (inner_end != std::string::npos) {
            auto non_ws = json.find_first_not_of(" \t\r\n", inner_start);
            if (non_ws != std::string::npos && non_ws < inner_end) {
                count = 1;
            }
        }
    } else if (inside) {
        count += 1;
    }

    return count;
}

// ---------------------------------------------------------------------------
// Finding builder
// ---------------------------------------------------------------------------

static std::string make_finding(const std::string& rule, const std::string& message, const std::string& severity) {
    return std::string("{\"rule\":\"") + rule + "\","
        + "\"message\":\"" + message + "\","
        + "\"severity\":\"" + severity + "\","
        + "\"location\":null}";
}

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

const char* karandash_contributed_rules() {
    return strdup("[\"cpp-title-case\", \"cpp-max-files\"]");
}

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    if (!rule_id || !mr_json || !config_json) return strdup("[]");

    const std::string rule(rule_id);
    const std::string mr(mr_json);
    const std::string config(config_json);

    if (rule == "cpp-title-case") {
        const std::string title = json_string(mr, "title");
        if (!title.empty() && !std::isupper(static_cast<unsigned char>(title[0]))) {
            const std::string finding = "[" + make_finding(
                "cpp-title-case",
                "MR title must start with an uppercase letter",
                "WARNING"
            ) + "]";
            return to_heap(finding);
        }
        return strdup("[]");
    }

    if (rule == "cpp-max-files") {
        const int max_files = json_int(config, "maxFiles", DEFAULT_MAX_FILES);
        const int file_count = json_array_count(mr, "changes");

        if (file_count > max_files) {
            std::ostringstream msg;
            msg << "MR touches " << file_count << " files; maximum allowed is " << max_files;
            const std::string finding = "[" + make_finding(
                "cpp-max-files",
                msg.str(),
                "ERROR"
            ) + "]";
            return to_heap(finding);
        }
        return strdup("[]");
    }

    return strdup("[]");
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}
