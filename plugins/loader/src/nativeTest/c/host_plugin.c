/*
 * ABI v2 CLI_COMMAND plugin for the host-channel tests (NativeHostChannelTest, JnaHostChannelJvmTest): takes the
 * channel in karandash_set_host and, from initialize, logs "hello", puts c/k=v into the host cache, reads it back and
 * logs "cache:hit" or "cache:miss". Every host response is released through the host's free_fn.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef char *(*karandash_host_call_fn)(void *host_ctx, const char *service, const char *request_json);
typedef void (*karandash_host_free_fn)(char *ptr);

static void *host_ctx;
static karandash_host_call_fn host_call;
static karandash_host_free_fn host_free;

int32_t karandash_set_host(int32_t abi, void *ctx, karandash_host_call_fn call, karandash_host_free_fn free_fn) {
    if (abi != 2) return 0;
    host_ctx = ctx;
    host_call = call;
    host_free = free_fn;
    return 1;
}

/* Calls the host and answers whether the response contains needle (NULL: any response). */
static int call_host(const char *service, const char *request, const char *needle) {
    char *response = host_call(host_ctx, service, request);
    if (response == NULL) return 0;
    int found = needle == NULL || strstr(response, needle) != NULL;
    host_free(response);
    return found;
}

int32_t karandash_abi_version(void) { return 2; }
const char *karandash_module_id(void) { return strdup("native-host"); }
const char *karandash_module_name(void) { return strdup("native-host"); }
const char *karandash_module_version(void) { return strdup("1.0.0"); }
const char *karandash_module_type(void) { return strdup("CLI_COMMAND"); }
void karandash_free_string(char *s) { free(s); }

int32_t karandash_initialize(const char *context_json) {
    (void) context_json;
    if (host_call == NULL) return 0;
    call_host("log", "{\"level\":\"info\",\"message\":\"hello\"}", NULL);
    call_host("cache.put", "{\"cache\":\"c\",\"key\":\"k\",\"value\":\"v\"}", NULL);
    int hit = call_host("cache.get", "{\"cache\":\"c\",\"key\":\"k\"}", "\"value\":\"v\"");
    call_host("log", hit ? "{\"message\":\"cache:hit\"}" : "{\"message\":\"cache:miss\"}", NULL);
    return 1;
}

void karandash_shutdown(void) {}

const char *karandash_cli_commands(void) { return strdup("[]"); }
