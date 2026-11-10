#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <unordered_map>
#include <atomic>

/**
 * C++ Comment Poster Plugin Example for Karandash
 *
 * Demonstrates the COMMENT_POSTER module type in C++.
 * Uses an in-memory comment store keyed by comment ID.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "cpp-comment-poster-example"
#define MODULE_NAME    "C++ Comment Poster Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "COMMENT_POSTER"

// In-memory comment store: id -> body
static std::unordered_map<std::string, std::string> g_comments;
static std::atomic<int> g_next_id{2000};

static char* to_heap(const std::string& s) {
    return strdup(s.c_str());
}

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
    g_comments.clear();
    g_next_id.store(2000);
    return 1;
}

void karandash_shutdown() {
    g_comments.clear();
}

const char* karandash_post_comment(
    const char* project_id,
    const char* mr_id,
    const char* body
) {
    const std::string id = std::to_string(g_next_id++);
    fprintf(stderr, "C++ Comment Poster: post_comment project=%s mr=%s id=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            id.c_str());

    g_comments[id] = body ? std::string(body) : "";
    return to_heap(id);
}

int32_t karandash_update_comment(
    const char* project_id,
    const char* mr_id,
    const char* comment_id,
    const char* body
) {
    fprintf(stderr, "C++ Comment Poster: update_comment project=%s mr=%s comment=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            comment_id ? comment_id : "(null)");

    if (comment_id) {
        g_comments[std::string(comment_id)] = body ? std::string(body) : "";
    }
    return 1;
}

const char* karandash_find_existing_comment(
    const char* project_id,
    const char* mr_id,
    const char* marker
) {
    fprintf(stderr, "C++ Comment Poster: find_existing_comment project=%s mr=%s marker=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            marker ? marker : "(null)");

    if (!marker) return nullptr;

    const std::string needle(marker);
    for (const auto& entry : g_comments) {
        if (entry.second.find(needle) != std::string::npos) {
            return to_heap(entry.first);
        }
    }
    return nullptr;
}

const char* karandash_post_inline_comment(
    const char* project_id,
    const char* mr_id,
    const char* body,
    const char* file_path,
    int32_t line,
    const char* diff_refs_json,
    int32_t start_line
) {
    const std::string id = std::to_string(g_next_id++);
    fprintf(stderr,
            "C++ Comment Poster: post_inline_comment project=%s mr=%s file=%s line=%d id=%s\n",
            project_id ? project_id : "(null)",
            mr_id ? mr_id : "(null)",
            file_path ? file_path : "(null)",
            line,
            id.c_str());
    (void)diff_refs_json;
    (void)start_line;

    g_comments[id] = body ? std::string(body) : "";
    return to_heap(id);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}

} // extern "C"
