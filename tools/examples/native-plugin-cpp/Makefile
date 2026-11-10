UNAME_S := $(shell uname -s)

CXX      = c++
# One shared header: the generated docs/native-sdk/karandash_plugin.h (override KARANDASH_SDK outside the repo).
KARANDASH_SDK ?= ../../../docs/native-sdk
CXXFLAGS = -std=c++17 -Wall -Wextra -fPIC -O2 -I$(KARANDASH_SDK)

ifeq ($(UNAME_S),Darwin)
    RULE_TARGET              = libcpp-rule-example.dylib
    REPORTER_TARGET          = libcpp-reporter-example.dylib
    PROVIDER_TARGET          = libcpp-provider-example.dylib
    MUTATOR_TARGET           = libcpp-mutator-example.dylib
    COMMENT_POSTER_TARGET    = libcpp-comment-poster-example.dylib
    CLI_COMMAND_TARGET       = libcpp-cli-command-example.dylib
    CONFIG_FORMAT_TARGET     = libcpp-config-format-example.dylib
    PROFILE_TARGET           = libcpp-profile-example.dylib
    AI_PROVIDER_TARGET       = libcpp-ai-provider-example.dylib
    AI_INTERCEPTOR_TARGET    = libcpp-ai-interceptor-example.dylib
    AI_ANALYZER_TARGET       = libcpp-ai-analyzer-example.dylib
    AI_PROMPT_PROC_TARGET    = libcpp-ai-prompt-processor-example.dylib
    AI_RESP_TRANS_TARGET     = libcpp-ai-response-transformer-example.dylib
    AI_CTX_ENRICH_TARGET     = libcpp-ai-context-enricher-example.dylib
    AI_REDACTOR_TARGET       = libcpp-ai-redactor-example.dylib
    AI_TOKEN_ALLOC_TARGET    = libcpp-ai-token-allocator-example.dylib
    SCRIPT_TARGET            = libcpp-script-example.dylib
    LDFLAGS                  = -dynamiclib
else ifeq ($(UNAME_S),Linux)
    RULE_TARGET              = libcpp-rule-example.so
    REPORTER_TARGET          = libcpp-reporter-example.so
    PROVIDER_TARGET          = libcpp-provider-example.so
    MUTATOR_TARGET           = libcpp-mutator-example.so
    COMMENT_POSTER_TARGET    = libcpp-comment-poster-example.so
    CLI_COMMAND_TARGET       = libcpp-cli-command-example.so
    CONFIG_FORMAT_TARGET     = libcpp-config-format-example.so
    PROFILE_TARGET           = libcpp-profile-example.so
    AI_PROVIDER_TARGET       = libcpp-ai-provider-example.so
    AI_INTERCEPTOR_TARGET    = libcpp-ai-interceptor-example.so
    AI_ANALYZER_TARGET       = libcpp-ai-analyzer-example.so
    AI_PROMPT_PROC_TARGET    = libcpp-ai-prompt-processor-example.so
    AI_RESP_TRANS_TARGET     = libcpp-ai-response-transformer-example.so
    AI_CTX_ENRICH_TARGET     = libcpp-ai-context-enricher-example.so
    AI_REDACTOR_TARGET       = libcpp-ai-redactor-example.so
    AI_TOKEN_ALLOC_TARGET    = libcpp-ai-token-allocator-example.so
    SCRIPT_TARGET            = libcpp-script-example.so
    LDFLAGS                  = -shared
else
    RULE_TARGET              = cpp-rule-example.dll
    REPORTER_TARGET          = cpp-reporter-example.dll
    PROVIDER_TARGET          = cpp-provider-example.dll
    MUTATOR_TARGET           = cpp-mutator-example.dll
    COMMENT_POSTER_TARGET    = cpp-comment-poster-example.dll
    CLI_COMMAND_TARGET       = cpp-cli-command-example.dll
    CONFIG_FORMAT_TARGET     = cpp-config-format-example.dll
    PROFILE_TARGET           = cpp-profile-example.dll
    AI_PROVIDER_TARGET       = cpp-ai-provider-example.dll
    AI_INTERCEPTOR_TARGET    = cpp-ai-interceptor-example.dll
    AI_ANALYZER_TARGET       = cpp-ai-analyzer-example.dll
    AI_PROMPT_PROC_TARGET    = cpp-ai-prompt-processor-example.dll
    AI_RESP_TRANS_TARGET     = cpp-ai-response-transformer-example.dll
    AI_CTX_ENRICH_TARGET     = cpp-ai-context-enricher-example.dll
    AI_REDACTOR_TARGET       = cpp-ai-redactor-example.dll
    AI_TOKEN_ALLOC_TARGET    = cpp-ai-token-allocator-example.dll
    SCRIPT_TARGET            = cpp-script-example.dll
    LDFLAGS                  = -shared
endif

ALL_TARGETS = $(RULE_TARGET) $(REPORTER_TARGET) $(PROVIDER_TARGET) \
              $(MUTATOR_TARGET) $(COMMENT_POSTER_TARGET) \
              $(CLI_COMMAND_TARGET) $(CONFIG_FORMAT_TARGET) $(PROFILE_TARGET) \
              $(AI_PROVIDER_TARGET) $(AI_INTERCEPTOR_TARGET) $(AI_ANALYZER_TARGET) \
              $(AI_PROMPT_PROC_TARGET) $(AI_RESP_TRANS_TARGET) $(AI_CTX_ENRICH_TARGET) \
              $(AI_REDACTOR_TARGET) $(AI_TOKEN_ALLOC_TARGET) $(SCRIPT_TARGET)

all: $(ALL_TARGETS)

$(RULE_TARGET): src/rule-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(REPORTER_TARGET): src/reporter-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(PROVIDER_TARGET): src/provider-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(MUTATOR_TARGET): src/mutator-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(COMMENT_POSTER_TARGET): src/comment-poster-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(CLI_COMMAND_TARGET): src/cli-command-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(CONFIG_FORMAT_TARGET): src/config-format-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(PROFILE_TARGET): src/profile-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_PROVIDER_TARGET): src/ai-provider-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_INTERCEPTOR_TARGET): src/ai-interceptor-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_ANALYZER_TARGET): src/ai-analyzer-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_PROMPT_PROC_TARGET): src/ai-prompt-processor-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_RESP_TRANS_TARGET): src/ai-response-transformer-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_CTX_ENRICH_TARGET): src/ai-context-enricher-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_REDACTOR_TARGET): src/ai-redactor-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(AI_TOKEN_ALLOC_TARGET): src/ai-token-allocator-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

$(SCRIPT_TARGET): src/script-plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ $<

clean:
	rm -f $(ALL_TARGETS) *.o

install: all
	mkdir -p ~/.karandash/plugins
	cp $(ALL_TARGETS) ~/.karandash/plugins/

info:
	@echo "Platform: $(UNAME_S)"
	@echo "Targets:  $(ALL_TARGETS)"
	@echo "Compiler: $(CXX)"
	@echo "Flags:    $(CXXFLAGS) $(LDFLAGS)"

.PHONY: all clean install info
