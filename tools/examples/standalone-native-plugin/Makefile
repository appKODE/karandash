# Simple Makefile for building native C plugins without CMake
# Detects platform and builds appropriate shared libraries

# Detect operating system
UNAME_S := $(shell uname -s)

# Compiler and flags
CC = gcc
# One shared header: the generated docs/native-sdk/karandash_plugin.h (override KARANDASH_SDK outside the repo).
KARANDASH_SDK ?= ../../../docs/native-sdk
CFLAGS = -std=gnu11 -Wall -Wextra -fPIC -O2 -I$(KARANDASH_SDK)

# Source files
SRC_RULE              = src/example-rule.c
SRC_REPORTER          = src/example-reporter.c
SRC_PROVIDER          = src/example-provider.c
SRC_MUTATOR           = src/example-mutator.c
SRC_COMMENT_POSTER    = src/example-comment-poster.c
SRC_CLI_COMMAND       = src/example-cli-command.c
SRC_CONFIG_FORMAT     = src/example-config-format.c
SRC_PROFILE           = src/example-profile.c
SRC_AI_PROVIDER       = src/example-ai-provider.c
SRC_AI_INTERCEPTOR    = src/example-ai-interceptor.c
SRC_AI_ANALYZER       = src/example-ai-analyzer.c
SRC_AI_PROMPT_PROC    = src/example-ai-prompt-processor.c
SRC_AI_RESP_TRANS     = src/example-ai-response-transformer.c
SRC_AI_CTX_ENRICH     = src/example-ai-context-enricher.c
SRC_AI_REDACTOR       = src/example-ai-redactor.c
SRC_AI_TOKEN_ALLOC    = src/example-ai-token-allocator.c
SRC_SCRIPT            = src/example-script.c

# Platform-specific settings
ifeq ($(UNAME_S),Darwin)
    RULE_TARGET              = libnative-example-rule.dylib
    REPORTER_TARGET          = libnative-example-reporter.dylib
    PROVIDER_TARGET          = libnative-example-provider.dylib
    MUTATOR_TARGET           = libnative-example-mutator.dylib
    COMMENT_POSTER_TARGET    = libnative-example-comment-poster.dylib
    CLI_COMMAND_TARGET       = libnative-example-cli-command.dylib
    CONFIG_FORMAT_TARGET     = libnative-example-config-format.dylib
    PROFILE_TARGET           = libnative-example-profile.dylib
    AI_PROVIDER_TARGET       = libnative-example-ai-provider.dylib
    AI_INTERCEPTOR_TARGET    = libnative-example-ai-interceptor.dylib
    AI_ANALYZER_TARGET       = libnative-example-ai-analyzer.dylib
    AI_PROMPT_PROC_TARGET    = libnative-example-ai-prompt-processor.dylib
    AI_RESP_TRANS_TARGET     = libnative-example-ai-response-transformer.dylib
    AI_CTX_ENRICH_TARGET     = libnative-example-ai-context-enricher.dylib
    AI_REDACTOR_TARGET       = libnative-example-ai-redactor.dylib
    AI_TOKEN_ALLOC_TARGET    = libnative-example-ai-token-allocator.dylib
    SCRIPT_TARGET            = libnative-example-script.dylib
    LDFLAGS                  = -dynamiclib
else ifeq ($(UNAME_S),Linux)
    RULE_TARGET              = libnative-example-rule.so
    REPORTER_TARGET          = libnative-example-reporter.so
    PROVIDER_TARGET          = libnative-example-provider.so
    MUTATOR_TARGET           = libnative-example-mutator.so
    COMMENT_POSTER_TARGET    = libnative-example-comment-poster.so
    CLI_COMMAND_TARGET       = libnative-example-cli-command.so
    CONFIG_FORMAT_TARGET     = libnative-example-config-format.so
    PROFILE_TARGET           = libnative-example-profile.so
    AI_PROVIDER_TARGET       = libnative-example-ai-provider.so
    AI_INTERCEPTOR_TARGET    = libnative-example-ai-interceptor.so
    AI_ANALYZER_TARGET       = libnative-example-ai-analyzer.so
    AI_PROMPT_PROC_TARGET    = libnative-example-ai-prompt-processor.so
    AI_RESP_TRANS_TARGET     = libnative-example-ai-response-transformer.so
    AI_CTX_ENRICH_TARGET     = libnative-example-ai-context-enricher.so
    AI_REDACTOR_TARGET       = libnative-example-ai-redactor.so
    AI_TOKEN_ALLOC_TARGET    = libnative-example-ai-token-allocator.so
    SCRIPT_TARGET            = libnative-example-script.so
    LDFLAGS                  = -shared
else
    # Windows (MinGW)
    RULE_TARGET              = native-example-rule.dll
    REPORTER_TARGET          = native-example-reporter.dll
    PROVIDER_TARGET          = native-example-provider.dll
    MUTATOR_TARGET           = native-example-mutator.dll
    COMMENT_POSTER_TARGET    = native-example-comment-poster.dll
    CLI_COMMAND_TARGET       = native-example-cli-command.dll
    CONFIG_FORMAT_TARGET     = native-example-config-format.dll
    PROFILE_TARGET           = native-example-profile.dll
    AI_PROVIDER_TARGET       = native-example-ai-provider.dll
    AI_INTERCEPTOR_TARGET    = native-example-ai-interceptor.dll
    AI_ANALYZER_TARGET       = native-example-ai-analyzer.dll
    AI_PROMPT_PROC_TARGET    = native-example-ai-prompt-processor.dll
    AI_RESP_TRANS_TARGET     = native-example-ai-response-transformer.dll
    AI_CTX_ENRICH_TARGET     = native-example-ai-context-enricher.dll
    AI_REDACTOR_TARGET       = native-example-ai-redactor.dll
    AI_TOKEN_ALLOC_TARGET    = native-example-ai-token-allocator.dll
    SCRIPT_TARGET            = native-example-script.dll
    LDFLAGS                  = -shared
endif

ALL_TARGETS = $(RULE_TARGET) $(REPORTER_TARGET) $(PROVIDER_TARGET) \
              $(MUTATOR_TARGET) $(COMMENT_POSTER_TARGET) \
              $(CLI_COMMAND_TARGET) $(CONFIG_FORMAT_TARGET) $(PROFILE_TARGET) \
              $(AI_PROVIDER_TARGET) $(AI_INTERCEPTOR_TARGET) $(AI_ANALYZER_TARGET) \
              $(AI_PROMPT_PROC_TARGET) $(AI_RESP_TRANS_TARGET) $(AI_CTX_ENRICH_TARGET) \
              $(AI_REDACTOR_TARGET) $(AI_TOKEN_ALLOC_TARGET) $(SCRIPT_TARGET)

# Build all targets
all: $(ALL_TARGETS)

$(RULE_TARGET): $(SRC_RULE) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_RULE)

$(REPORTER_TARGET): $(SRC_REPORTER) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_REPORTER)

$(PROVIDER_TARGET): $(SRC_PROVIDER) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_PROVIDER)

$(MUTATOR_TARGET): $(SRC_MUTATOR) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_MUTATOR)

$(COMMENT_POSTER_TARGET): $(SRC_COMMENT_POSTER) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_COMMENT_POSTER)

$(CLI_COMMAND_TARGET): $(SRC_CLI_COMMAND) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_CLI_COMMAND)

$(CONFIG_FORMAT_TARGET): $(SRC_CONFIG_FORMAT) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_CONFIG_FORMAT)

$(PROFILE_TARGET): $(SRC_PROFILE) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_PROFILE)

$(AI_PROVIDER_TARGET): $(SRC_AI_PROVIDER) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_PROVIDER)

$(AI_INTERCEPTOR_TARGET): $(SRC_AI_INTERCEPTOR) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_INTERCEPTOR)

$(AI_ANALYZER_TARGET): $(SRC_AI_ANALYZER) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_ANALYZER)

$(AI_PROMPT_PROC_TARGET): $(SRC_AI_PROMPT_PROC) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_PROMPT_PROC)

$(AI_RESP_TRANS_TARGET): $(SRC_AI_RESP_TRANS) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_RESP_TRANS)

$(AI_CTX_ENRICH_TARGET): $(SRC_AI_CTX_ENRICH) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_CTX_ENRICH)

$(AI_REDACTOR_TARGET): $(SRC_AI_REDACTOR) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_REDACTOR)

$(AI_TOKEN_ALLOC_TARGET): $(SRC_AI_TOKEN_ALLOC) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_AI_TOKEN_ALLOC)

$(SCRIPT_TARGET): $(SRC_SCRIPT) $(KARANDASH_SDK)/karandash_plugin.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SRC_SCRIPT)

# Clean build artifacts
clean:
	rm -f $(ALL_TARGETS) *.o

# Install to Karandash plugins directory
install: $(ALL_TARGETS)
	mkdir -p ~/.karandash/plugins
	cp $(ALL_TARGETS) ~/.karandash/plugins/

# Show build information
info:
	@echo "Platform: $(UNAME_S)"
	@echo "Targets:  $(ALL_TARGETS)"
	@echo "Compiler: $(CC)"
	@echo "Flags:    $(CFLAGS) $(LDFLAGS)"

.PHONY: all clean install info
