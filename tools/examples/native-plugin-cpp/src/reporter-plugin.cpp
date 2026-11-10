#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <sstream>
#include <vector>

#define MODULE_ID      "cpp-reporter-example"
#define MODULE_NAME    "C++ Markdown Reporter Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "REPORTER"

// ---------------------------------------------------------------------------
// Minimal JSON array parser for findings
// ---------------------------------------------------------------------------

struct Finding {
    std::string severity;
    std::string rule;
    std::string message;
};

/**
 * Extract a string field from a JSON object fragment.
 */
static std::string field_value(const std::string& obj, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    auto key_pos = obj.find(needle);
    if (key_pos == std::string::npos) return {};

    auto colon = obj.find(':', key_pos + needle.size());
    if (colon == std::string::npos) return {};

    auto quote_open = obj.find('"', colon + 1);
    if (quote_open == std::string::npos) return {};

    std::string value;
    for (auto i = quote_open + 1; i < obj.size(); ++i) {
        if (obj[i] == '\\' && i + 1 < obj.size()) {
            ++i;
            continue;
        }
        if (obj[i] == '"') break;
        value += obj[i];
    }
    return value;
}

/**
 * Split a JSON array string into individual object strings.
 */
static std::vector<std::string> split_json_array(const std::string& json) {
    std::vector<std::string> items;

    auto start = json.find('[');
    if (start == std::string::npos) return items;

    int depth = 0;
    std::size_t obj_start = std::string::npos;

    for (auto i = start; i < json.size(); ++i) {
        char c = json[i];
        if (c == '{') {
            if (depth == 1) obj_start = i;
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 1 && obj_start != std::string::npos) {
                items.push_back(json.substr(obj_start, i - obj_start + 1));
                obj_start = std::string::npos;
            }
        } else if (c == '[') {
            ++depth;
        } else if (c == ']') {
            --depth;
            if (depth == 0) break;
        }
    }
    return items;
}

static std::vector<Finding> parse_findings(const std::string& json) {
    std::vector<Finding> findings;
    for (const auto& obj : split_json_array(json)) {
        Finding f;
        f.severity = field_value(obj, "severity");
        f.rule     = field_value(obj, "rule");
        f.message  = field_value(obj, "message");
        findings.push_back(f);
    }
    return findings;
}

// ---------------------------------------------------------------------------
// Markdown report builder
// ---------------------------------------------------------------------------

static std::string severity_badge(const std::string& severity) {
    if (severity == "ERROR")   return "ERROR";
    if (severity == "WARNING") return "WARNING";
    return "INFO";
}

static std::string build_markdown(const std::vector<Finding>& findings, int32_t passed) {
    std::ostringstream out;
    out << "# Karandash Validation Report\n\n";

    const int total = static_cast<int>(findings.size()) + passed;
    out << "**Rules checked:** " << total << "  \n";
    out << "**Passed:** " << passed << "  \n";
    out << "**Findings:** " << findings.size() << "\n\n";

    if (findings.empty()) {
        out << "All rules passed.\n";
        return out.str();
    }

    out << "## Findings\n\n";
    out << "| Severity | Rule | Message |\n";
    out << "|----------|------|---------|\n";

    for (const auto& f : findings) {
        out << "| " << severity_badge(f.severity)
            << " | `" << f.rule << "`"
            << " | " << f.message << " |\n";
    }

    return out.str();
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

const char* karandash_format_id() {
    return strdup("markdown");
}

const char* karandash_report(
    const char* findings_json,
    int32_t passed,
    const char* /*config_json*/
) {
    if (!findings_json) return strdup("# Karandash Validation Report\n\nNo data.\n");

    const auto findings = parse_findings(std::string(findings_json));
    const std::string report = build_markdown(findings, passed);
    return strdup(report.c_str());
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}
