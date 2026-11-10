/*
 * Booby-trapped native plugin for NativePluginInspectorStaticTest: loading the library runs the
 * constructor, which creates the file named by BOOBY_TRAP_MARKER. Inspection must never trigger it.
 */
#include <stdio.h>
#include <stdlib.h>

__attribute__((constructor)) static void spring_trap(void) {
    const char *marker = getenv("BOOBY_TRAP_MARKER");
    if (marker == NULL) return;
    FILE *f = fopen(marker, "w");
    if (f != NULL) {
        fputs("loaded\n", f);
        fclose(f);
    }
}

int karandash_abi_version(void) { return 1; }

const char *karandash_module_id(void) { return "booby-trap"; }
