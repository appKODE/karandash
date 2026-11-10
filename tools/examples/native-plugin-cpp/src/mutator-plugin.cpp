#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <unordered_set>

/**
 * C++ MR Mutator Plugin Example for Karandash
 *
 * Demonstrates the MERGE_REQUEST_MUTATOR module type in C++.
 * Uses an in-memory label store; all operations succeed.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "cpp-mutator-example"
#define MODULE_NAME    "C++ MR Mutator Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "MERGE_REQUEST_MUTATOR"

// In-memory state for demo purposes
static std::unordered_set<std::string> g_labels;
static bool g_draft = false;

// ---------------------------------------------------------------------------
// ABI exports
// ---------------------------------------------------------------------------

extern "C" {

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
    g_labels.clear();
    g_draft = false;
    return 1;
}

void karandash_shutdown() {
    g_labels.clear();
}

int32_t karandash_add_labels(
    const char* project_id,
    const char* mr_id,
    const char* labels_json
) {
    fprintf(stderr, "C++ Mutator: add_labels project=%s mr=%s labels=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            labels_json ? labels_json : "[]");

    // Parse the JSON array of label strings - simple scan without a full parser
    if (labels_json) {
        const char* p = labels_json;
        while ((p = strchr(p, '"')) != nullptr) {
            ++p;
            const char* end = strchr(p, '"');
            if (!end) break;
            g_labels.insert(std::string(p, end));
            p = end + 1;
        }
    }
    return 1;
}

int32_t karandash_remove_labels(
    const char* project_id,
    const char* mr_id,
    const char* labels_json
) {
    fprintf(stderr, "C++ Mutator: remove_labels project=%s mr=%s labels=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            labels_json ? labels_json : "[]");

    if (labels_json) {
        const char* p = labels_json;
        while ((p = strchr(p, '"')) != nullptr) {
            ++p;
            const char* end = strchr(p, '"');
            if (!end) break;
            g_labels.erase(std::string(p, end));
            p = end + 1;
        }
    }
    return 1;
}

int32_t karandash_set_draft(
    const char* project_id,
    const char* mr_id,
    int32_t draft
) {
    fprintf(stderr, "C++ Mutator: set_draft project=%s mr=%s draft=%d\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            draft);
    g_draft = (draft != 0);
    return 1;
}

int32_t karandash_update_title(
    const char* project_id,
    const char* mr_id,
    const char* title
) {
    fprintf(stderr, "C++ Mutator: update_title project=%s mr=%s title=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            title ? title : "(null)");
    return 1;
}

int32_t karandash_update_description(
    const char* project_id,
    const char* mr_id,
    const char* description
) {
    fprintf(stderr, "C++ Mutator: update_description project=%s mr=%s description=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            description ? description : "(null)");
    return 1;
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}

} // extern "C"
