#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/**
 * Standalone Native Rule Plugin for Karandash
 *
 * This is a complete, self-contained example of a Karandash rule plugin
 * written in C. It demonstrates:
 * - Native plugin ABI v1 implementation
 * - All required function exports
 * - JSON string handling
 * - Memory management with strdup/free
 *
 * Build: mkdir build && cd build && cmake .. && make
 * Install: cp libnative-example.{so,dylib,dll} ~/.karandash/plugins/
 */

/* Module metadata */
#define MODULE_ID "example-native-plugin"
#define MODULE_NAME "Example Native Rule Plugin"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE "RULE"

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
    /* Parse context_json to get host information if needed */
    /* For this simple example, just log and return success */
    fprintf(stderr, "Example Native Plugin initialized\n");
    return 1;  /* Return 1 for success, 0 for failure */
}

void karandash_shutdown(void) {
    fprintf(stderr, "Example Native Plugin shutting down\n");
    /* Cleanup any resources here */
}

/* Rule module functions */

const char* karandash_contributed_rules(void) {
    /* Return JSON array of rule IDs this plugin provides */
    return strdup("[\"native-title-uppercase\", \"native-branch-prefix\"]");
}

/**
 * Simple helper to check if string starts with prefix (case-insensitive)
 */
static int starts_with_ignore_case(const char* str, const char* prefix) {
    size_t prefix_len = strlen(prefix);
    if (strlen(str) < prefix_len) {
        return 0;
    }
    for (size_t i = 0; i < prefix_len; i++) {
        char c1 = str[i];
        char c2 = prefix[i];
        if (c1 >= 'A' && c1 <= 'Z') c1 += 32;
        if (c2 >= 'A' && c2 <= 'Z') c2 += 32;
        if (c1 != c2) return 0;
    }
    return 1;
}

/**
 * Validate a merge request
 *
 * In a real plugin, you'd use a JSON parser like cJSON to parse mr_json and config_json.
 * This example uses simple string matching for demonstration.
 *
 * @param rule_id - The rule ID to validate
 * @param mr_json - JSON string containing merge request data
 * @param config_json - JSON string containing rule configuration
 * @return JSON array of findings (heap-allocated, caller must free via karandash_free_string)
 */
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    /* Simple validation examples without full JSON parsing */
    /* In production, use a JSON library like cJSON, jansson, or json-c */

    if (strcmp(rule_id, "native-title-uppercase") == 0) {
        /* Check if title contains any uppercase letter */
        /* For demo, we'll extract title from JSON manually (not recommended for production) */

        /* Look for "title": in the JSON */
        const char* title_key = "\"title\":";
        const char* title_pos = strstr(mr_json, title_key);

        if (title_pos != NULL) {
            /* Skip to opening quote */
            const char* title_start = strchr(title_pos + strlen(title_key), '"');
            if (title_start != NULL) {
                title_start++; /* Skip opening quote */
                const char* title_end = strchr(title_start, '"');

                if (title_end != NULL) {
                    /* Check if any character is uppercase */
                    int has_uppercase = 0;
                    for (const char* p = title_start; p < title_end; p++) {
                        if (*p >= 'A' && *p <= 'Z') {
                            has_uppercase = 1;
                            break;
                        }
                    }

                    if (!has_uppercase) {
                        /* Return a finding */
                        char* result = strdup(
                            "[{\"rule\":\"native-title-uppercase\","
                            "\"message\":\"Title should contain at least one uppercase letter\","
                            "\"severity\":\"WARNING\","
                            "\"location\":null}]"
                        );
                        return result;
                    }
                }
            }
        }

        /* No violation found */
        return strdup("[]");
    }

    if (strcmp(rule_id, "native-branch-prefix") == 0) {
        /* Check if source branch starts with feature/, bugfix/, or hotfix/ */
        const char* branch_key = "\"source_branch\":";
        const char* branch_pos = strstr(mr_json, branch_key);

        if (branch_pos != NULL) {
            const char* branch_start = strchr(branch_pos + strlen(branch_key), '"');
            if (branch_start != NULL) {
                branch_start++;
                const char* branch_end = strchr(branch_start, '"');

                if (branch_end != NULL) {
                    /* Extract branch name */
                    size_t branch_len = branch_end - branch_start;
                    char* branch = malloc(branch_len + 1);
                    strncpy(branch, branch_start, branch_len);
                    branch[branch_len] = '\0';

                    /* Check if it starts with valid prefix */
                    int valid = starts_with_ignore_case(branch, "feature/") ||
                               starts_with_ignore_case(branch, "bugfix/") ||
                               starts_with_ignore_case(branch, "hotfix/");

                    if (!valid) {
                        free(branch);
                        char* result = strdup(
                            "[{\"rule\":\"native-branch-prefix\","
                            "\"message\":\"Source branch must start with feature/, bugfix/, or hotfix/\","
                            "\"severity\":\"ERROR\","
                            "\"location\":null}]"
                        );
                        return result;
                    }

                    free(branch);
                }
            }
        }

        return strdup("[]");
    }

    /* Unknown rule */
    return strdup("[]");
}

/* Memory management */

void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
