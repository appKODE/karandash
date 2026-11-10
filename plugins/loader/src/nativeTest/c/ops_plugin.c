/*
 * ABI compile test for CInteropTransportAbiTest. Built by Gradle with -Wall -Wextra -Werror -Wmissing-prototypes
 * against the generated docs/native-sdk/karandash_plugin.h, so every definition here must match a generated
 * prototype exactly. One op per arity in BridgeAbi.OPS (0, 1, 2, 3, 4, 5 and 7) plus int32_t parameters and
 * VOID / INT / BOOL results; each string op echoes its arguments joined by '|'.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "karandash_plugin.h"

static int32_t g_completed = 0;

static const char *echo(int count, const char **parts) {
    size_t size = 1;
    for (int i = 0; i < count; i++) size += strlen(parts[i]) + 1;
    char *out = malloc(size);
    out[0] = '\0';
    for (int i = 0; i < count; i++) {
        if (i > 0) strcat(out, "|");
        strcat(out, parts[i]);
    }
    return out;
}

int32_t karandash_abi_version(void) { return 1; }
void karandash_free_string(const char *ptr) { free((void *) ptr); }

const char *karandash_module_id(void) { return strdup("ops"); }
const char *karandash_module_description(void) {
    char text[16];
    snprintf(text, sizeof(text), "%d", g_completed);
    return strdup(text);
}

int32_t karandash_initialize(const char *context_json) { return strcmp(context_json, "{}") == 0; }
void karandash_on_pipeline_complete(void) { g_completed++; }

const char *karandash_fetch_merge_request_by_url(const char *url) {
    const char *parts[] = {url};
    return echo(1, parts);
}

const char *karandash_fetch_merge_request(const char *project_id, const char *mr_id) {
    const char *parts[] = {project_id, mr_id};
    return echo(2, parts);
}

const char *karandash_validate(const char *rule_id, const char *mr_json, const char *config_json) {
    const char *parts[] = {rule_id, mr_json, config_json};
    return echo(3, parts);
}

int32_t karandash_update_comment(const char *project_id, const char *mr_id, const char *comment_id, const char *body) {
    return strcmp(project_id, "p") == 0 && strcmp(mr_id, "m") == 0 && strcmp(comment_id, "c") == 0 &&
           strcmp(body, "b") == 0;
}

const char *karandash_update_note(const char *project_id, const char *mr_id, const char *thread_id, const char *note_id,
                                  const char *body) {
    const char *parts[] = {project_id, mr_id, thread_id, note_id, body};
    return echo(5, parts);
}

const char *karandash_post_inline_comment(const char *project_id, const char *mr_id, const char *body,
                                          const char *file_path, int32_t line, const char *diff_refs_json,
                                          int32_t start_line) {
    char line_text[16];
    char start_text[16];
    snprintf(line_text, sizeof(line_text), "%d", line);
    snprintf(start_text, sizeof(start_text), "%d", start_line);
    const char *parts[] = {project_id, mr_id, body, file_path, line_text, diff_refs_json, start_text};
    return echo(7, parts);
}

int32_t karandash_set_draft(const char *project_id, const char *mr_id, int32_t draft) {
    (void) project_id;
    (void) mr_id;
    return draft;
}

int32_t karandash_delete_marked_inline_comments(const char *project_id, const char *mr_id, const char *marker) {
    (void) project_id;
    (void) mr_id;
    return (int32_t) strlen(marker);
}
