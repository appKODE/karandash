#include "karandash_plugin.h"

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>

/**
 * C++ MR Provider Plugin Example for Karandash
 *
 * Demonstrates the MERGE_REQUEST_PROVIDER module type in C++.
 * Returns mock merge request JSON for any project/MR ID or URL.
 *
 * Build: cmake -S . -B build && cmake --build build
 */

#define MODULE_ID      "cpp-provider-example"
#define MODULE_NAME    "C++ MR Provider Plugin Example"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE    "MERGE_REQUEST_PROVIDER"

// ---------------------------------------------------------------------------
// Mock MR JSON
// ---------------------------------------------------------------------------

static const std::string MOCK_MR_JSON =
    R"({"title":"Mock MR","description":"This is a mock merge request returned by the C++ example provider.","state":"opened","sourceBranch":"feature/mock-branch","targetBranch":"main","author":{"username":"mock","name":"Mock User"},"labels":[],"commits":[{"sha":"abc1234","message":"feat: add mock feature","author":"mock"}],"changedFiles":[{"path":"src/mock.cpp","changeType":"ADDED","additions":42,"deletions":0}],"comments":[],"approvals":[]})";

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
    return 1;
}

void karandash_shutdown() {}

const char* karandash_fetch_merge_request(const char* project_id, const char* mr_id) {
    fprintf(stderr, "C++ Provider: fetching MR %s from project %s\n",
            mr_id ? mr_id : "(null)",
            project_id ? project_id : "(null)");
    return to_heap(MOCK_MR_JSON);
}

const char* karandash_fetch_merge_request_by_url(const char* url) {
    fprintf(stderr, "C++ Provider: fetching MR by URL %s\n", url ? url : "(null)");
    return to_heap(MOCK_MR_JSON);
}

void karandash_free_string(const char* ptr) {
    if (ptr) free(const_cast<char*>(ptr));
}

} // extern "C"
