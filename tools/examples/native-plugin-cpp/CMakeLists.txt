cmake_minimum_required(VERSION 3.14)
project(native-plugin-cpp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# One shared header: the generated docs/native-sdk/karandash_plugin.h (override KARANDASH_SDK outside the repo).
set(KARANDASH_SDK "${CMAKE_CURRENT_SOURCE_DIR}/../../../docs/native-sdk" CACHE PATH "Directory holding karandash_plugin.h")
include_directories(${KARANDASH_SDK})

# ---------------------------------------------------------------------------
# Helper macro: configure shared library output name per platform
# ---------------------------------------------------------------------------
macro(configure_shared_lib target output_name)
    target_compile_options(${target} PRIVATE -Wall -Wextra -O2)
    if(APPLE)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".dylib"
            PREFIX "lib"
        )
    elseif(UNIX)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".so"
            PREFIX "lib"
        )
    elseif(WIN32)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".dll"
            PREFIX ""
        )
    endif()
endmacro()

# ---------------------------------------------------------------------------
# Rule plugin
# ---------------------------------------------------------------------------
add_library(cpp-rule-example SHARED src/rule-plugin.cpp)
configure_shared_lib(cpp-rule-example "cpp-rule-example")

# ---------------------------------------------------------------------------
# Reporter plugin
# ---------------------------------------------------------------------------
add_library(cpp-reporter-example SHARED src/reporter-plugin.cpp)
configure_shared_lib(cpp-reporter-example "cpp-reporter-example")

# ---------------------------------------------------------------------------
# MR Provider plugin
# ---------------------------------------------------------------------------
add_library(cpp-provider-example SHARED src/provider-plugin.cpp)
configure_shared_lib(cpp-provider-example "cpp-provider-example")

# ---------------------------------------------------------------------------
# MR Mutator plugin
# ---------------------------------------------------------------------------
add_library(cpp-mutator-example SHARED src/mutator-plugin.cpp)
configure_shared_lib(cpp-mutator-example "cpp-mutator-example")

# ---------------------------------------------------------------------------
# Comment Poster plugin
# ---------------------------------------------------------------------------
add_library(cpp-comment-poster-example SHARED src/comment-poster-plugin.cpp)
configure_shared_lib(cpp-comment-poster-example "cpp-comment-poster-example")

# ---------------------------------------------------------------------------
# CLI Command plugin
# ---------------------------------------------------------------------------
add_library(cpp-cli-command-example SHARED src/cli-command-plugin.cpp)
configure_shared_lib(cpp-cli-command-example "cpp-cli-command-example")

# ---------------------------------------------------------------------------
# Config Format plugin
# ---------------------------------------------------------------------------
add_library(cpp-config-format-example SHARED src/config-format-plugin.cpp)
configure_shared_lib(cpp-config-format-example "cpp-config-format-example")

# ---------------------------------------------------------------------------
# Profile plugin
# ---------------------------------------------------------------------------
add_library(cpp-profile-example SHARED src/profile-plugin.cpp)
configure_shared_lib(cpp-profile-example "cpp-profile-example")

# ---------------------------------------------------------------------------
# AI Provider plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-provider-example SHARED src/ai-provider-plugin.cpp)
configure_shared_lib(cpp-ai-provider-example "cpp-ai-provider-example")

# ---------------------------------------------------------------------------
# AI Interceptor plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-interceptor-example SHARED src/ai-interceptor-plugin.cpp)
configure_shared_lib(cpp-ai-interceptor-example "cpp-ai-interceptor-example")

# ---------------------------------------------------------------------------
# AI Analyzer plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-analyzer-example SHARED src/ai-analyzer-plugin.cpp)
configure_shared_lib(cpp-ai-analyzer-example "cpp-ai-analyzer-example")

# ---------------------------------------------------------------------------
# AI Prompt Processor plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-prompt-processor-example SHARED src/ai-prompt-processor-plugin.cpp)
configure_shared_lib(cpp-ai-prompt-processor-example "cpp-ai-prompt-processor-example")

# ---------------------------------------------------------------------------
# AI Response Transformer plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-response-transformer-example SHARED src/ai-response-transformer-plugin.cpp)
configure_shared_lib(cpp-ai-response-transformer-example "cpp-ai-response-transformer-example")

# ---------------------------------------------------------------------------
# AI Context Enricher plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-context-enricher-example SHARED src/ai-context-enricher-plugin.cpp)
configure_shared_lib(cpp-ai-context-enricher-example "cpp-ai-context-enricher-example")

# ---------------------------------------------------------------------------
# AI Redactor plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-redactor-example SHARED src/ai-redactor-plugin.cpp)
configure_shared_lib(cpp-ai-redactor-example "cpp-ai-redactor-example")

# ---------------------------------------------------------------------------
# AI Token Allocator plugin
# ---------------------------------------------------------------------------
add_library(cpp-ai-token-allocator-example SHARED src/ai-token-allocator-plugin.cpp)
configure_shared_lib(cpp-ai-token-allocator-example "cpp-ai-token-allocator-example")

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
install(TARGETS
    cpp-rule-example
    cpp-reporter-example
    cpp-provider-example
    cpp-mutator-example
    cpp-comment-poster-example
    cpp-cli-command-example
    cpp-config-format-example
    cpp-profile-example
    cpp-ai-provider-example
    cpp-ai-interceptor-example
    cpp-ai-analyzer-example
    cpp-ai-prompt-processor-example
    cpp-ai-response-transformer-example
    cpp-ai-context-enricher-example
    cpp-ai-redactor-example
    cpp-ai-token-allocator-example
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
