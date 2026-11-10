/*
 * Minimal native CLI_COMMAND plugin (initialized while loading) for NativePluginLoaderLifecycleTest. Built twice by Gradle:
 * -DPLUGIN_ID="native-ok" -DINIT_RESULT=1 and -DPLUGIN_ID="native-refuses" -DINIT_RESULT=0.
 * initialize/shutdown append "init:<id>" / "shutdown:<id>" lines to the file named by LIFECYCLE_LOG.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void record(const char *event) {
    const char *log = getenv("LIFECYCLE_LOG");
    if (log == NULL) return;
    FILE *f = fopen(log, "a");
    if (f != NULL) {
        fprintf(f, "%s:%s\n", event, PLUGIN_ID);
        fclose(f);
    }
}

int32_t karandash_abi_version(void) { return 1; }
const char *karandash_module_id(void) { return strdup(PLUGIN_ID); }
const char *karandash_module_name(void) { return strdup(PLUGIN_ID); }
const char *karandash_module_version(void) { return strdup("1.0.0"); }
const char *karandash_module_type(void) { return strdup("CLI_COMMAND"); }
void karandash_free_string(char *s) { free(s); }

int32_t karandash_initialize(const char *context_json) {
    (void) context_json;
    record("init");
    return INIT_RESULT;
}

void karandash_shutdown(void) { record("shutdown"); }

const char *karandash_cli_commands(void) { return strdup("[]"); }
